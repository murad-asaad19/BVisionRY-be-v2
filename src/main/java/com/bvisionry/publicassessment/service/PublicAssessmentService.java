package com.bvisionry.publicassessment.service;

import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.dto.AnswerResponse;
import com.bvisionry.assessment.dto.AssessmentDetailResponse;
import com.bvisionry.assessment.dto.BatchSaveAnswersRequest;
import com.bvisionry.assessment.dto.ReviewResponse;
import com.bvisionry.assessment.dto.SaveAnswerRequest;
import com.bvisionry.assessment.dto.SubmissionStatusResponse;
import com.bvisionry.assessment.dto.SubmitAssessmentResponse;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.evaluation.EvaluationService;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.pipeline.SystemQuestion;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.publicassessment.dto.CreatePublicAssessmentLinkRequest;
import com.bvisionry.publicassessment.dto.PublicAssessmentLinkDto;
import com.bvisionry.publicassessment.dto.PublicAssessmentLinkInfoResponse;
import com.bvisionry.publicassessment.dto.PublicAssessmentSessionRequest;
import com.bvisionry.publicassessment.dto.PublicAssessmentSessionResponse;
import com.bvisionry.publicassessment.dto.PublicSubmissionResponsePageDto;
import com.bvisionry.publicassessment.dto.UpdatePublicAssessmentLinkRequest;
import com.bvisionry.publicassessment.entity.PublicAssessmentLink;
import com.bvisionry.publicassessment.entity.PublicAssessmentLinkStatus;
import com.bvisionry.publicassessment.repository.PublicAssessmentLinkRepository;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PersonalInfoEntry;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import com.bvisionry.reporting.dto.PillarScoreSummary;
import com.bvisionry.reporting.service.MemberResultsService;
import com.bvisionry.reporting.service.PersonalInfoResolver;
import com.bvisionry.survey.entity.RespondentFieldMode;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Anonymous public-assessment flow + admin CRUD for public links.
 *
 * <p>The taker methods mirror {@link com.bvisionry.assessment.AssessmentService}
 * but are keyed by the per-session {@code accessToken} instead of an owning
 * user, and resolve the {@link Pipeline} via
 * {@code submission.getPublicLink().getPipeline()} — public submissions have
 * no assignment, so none of the member helpers that dereference it can be
 * reused here.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicAssessmentService {

    /** Effective-status value the FE sees when an ACTIVE link hits its cap. */
    static final String STATUS_MAX_RESPONSES_REACHED = "MAX_RESPONSES_REACHED";

    private final PublicAssessmentLinkRepository linkRepository;
    private final SubmissionRepository submissionRepository;
    private final AnswerRepository answerRepository;
    private final PipelineRepository pipelineRepository;
    private final EvaluationService evaluationService;
    private final MemberResultsService memberResultsService;
    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final OverallSummaryRepository overallSummaryRepository;
    private final PersonalInfoResolver personalInfoResolver;

    // ---- Anonymous respondent flow ----

    /**
     * Link info for the public landing page. Always returns the link with its
     * effective status — even DISABLED/ARCHIVED/cap-reached — so the FE can
     * render a friendly terminal message instead of a bare 404. The question
     * tree is only included while the link is actually takeable.
     */
    @Transactional(readOnly = true)
    public PublicAssessmentLinkInfoResponse getLinkInfo(UUID token) {
        PublicAssessmentLink link = linkRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Public assessment", token.toString()));

        String effectiveStatus = effectiveStatus(link);
        boolean takeable = PublicAssessmentLinkStatus.ACTIVE.name().equals(effectiveStatus);
        List<AssessmentDetailResponse.PillarSection> pillars = takeable
                ? buildPillarSections(link.getPipeline(), Map.of(), link.getGenderMode())
                : List.of();

        return new PublicAssessmentLinkInfoResponse(
                link.getId(),
                link.getToken(),
                link.getTitle(),
                link.getDescription(),
                link.getPipeline().getName(),
                effectiveStatus,
                link.getRespondentEmailMode(),
                link.getRespondentNameMode(),
                link.isShowResultsToRespondent(),
                pillars
        );
    }

    @Transactional
    public PublicAssessmentSessionResponse createSession(UUID token,
                                                         PublicAssessmentSessionRequest request) {
        PublicAssessmentLink link = linkRepository.findByToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Public assessment", token.toString()));

        if (link.getStatus() != PublicAssessmentLinkStatus.ACTIVE) {
            throw new ResponseStatusException(HttpStatus.GONE,
                    "This assessment link is no longer accepting responses");
        }

        validateFieldMode(link.getRespondentEmailMode(), request.respondentEmail(), "email");
        validateFieldMode(link.getRespondentNameMode(), request.respondentName(), "name");

        // Atomic cap gate — the UPDATE only applies while responseCount is
        // below maxResponses, so concurrent session-creates can't race past
        // the cap. 0 rows updated = the cap is already reached.
        if (linkRepository.incrementResponseCount(link.getId()) == 0) {
            throw new IllegalOperationException(
                    "This assessment has reached its maximum number of responses");
        }

        Submission submission = new Submission();
        submission.setPublicLink(link);
        submission.setRespondentEmail(normalize(request.respondentEmail()));
        submission.setRespondentName(normalize(request.respondentName()));
        submission.setAccessToken(UUID.randomUUID());
        submission = submissionRepository.save(submission);

        log.info("Public assessment session {} started on link {}", submission.getId(), link.getId());
        return new PublicAssessmentSessionResponse(submission.getAccessToken(), submission.getId());
    }

    @Transactional(readOnly = true)
    public AssessmentDetailResponse getSessionDetail(UUID accessToken) {
        Submission submission = getSessionSubmission(accessToken);
        Pipeline pipeline = submission.getPublicLink().getPipeline();

        List<Answer> answers = answerRepository.findBySubmissionId(submission.getId());
        Map<UUID, Answer> answersByQuestion = answers.stream()
                .collect(Collectors.toMap(
                        a -> a.getQuestion().getId(),
                        a -> a,
                        PublicAssessmentService::mostRecentAnswer));

        return new AssessmentDetailResponse(
                submission.getId(),
                null, // no assignment for public submissions
                submission.getStatus(),
                null, // no deadline for public submissions
                new AssessmentDetailResponse.PipelineInfo(
                        pipeline.getId(), pipeline.getName(), pipeline.getDescription()),
                buildPillarSections(pipeline, answersByQuestion,
                        submission.getPublicLink().getGenderMode()),
                List.of() // public submissions never enter PENDING_REEDIT
        );
    }

    @Transactional
    public List<AnswerResponse> batchSaveAnswers(UUID accessToken, BatchSaveAnswersRequest request) {
        Submission submission = getSessionSubmission(accessToken);
        validateEditable(submission);

        return request.answers().stream()
                .map(entry -> {
                    SaveAnswerRequest singleRequest = new SaveAnswerRequest(
                            entry.responseText(), entry.selectedValue());
                    return saveAnswerInternal(submission, entry.questionId(), singleRequest);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public ReviewResponse getReview(UUID accessToken) {
        Submission submission = getSessionSubmission(accessToken);
        return buildReview(submission);
    }

    @Transactional
    public SubmitAssessmentResponse submit(UUID accessToken) {
        Submission submission = getSessionSubmission(accessToken);

        if (submission.getStatus() == SubmissionStatus.SUBMITTED) {
            throw new BadRequestException("Assessment is currently being evaluated. Please wait.");
        }
        if (submission.getStatus() == SubmissionStatus.EVALUATED) {
            throw new BadRequestException("Assessment has already been evaluated.");
        }
        // Re-submit from FAILED is allowed, mirroring the member flow.

        // Verify all required questions are answered
        ReviewResponse review = buildReview(submission);
        if (!review.complete()) {
            throw new BadRequestException(
                    "Cannot submit: " + review.unansweredQuestions().size() + " unanswered required questions");
        }

        // Lock submission
        submission.setStatus(SubmissionStatus.SUBMITTED);
        submission.setSubmittedAt(Instant.now());
        submissionRepository.save(submission);

        UUID submissionId = submission.getId();
        AfterCommit.run(() -> {
            log.info("Transaction committed for public submission {}, dispatching async evaluation",
                    submissionId);
            evaluationService.evaluateSubmissionAsync(submissionId);
        });

        log.info("Public submission {} submitted and enqueued for evaluation", submissionId);

        return new SubmitAssessmentResponse(
                submissionId,
                SubmissionStatus.SUBMITTED,
                submission.getSubmittedAt(),
                "Assessment submitted successfully. Evaluation in progress.",
                null // no post-completion CTA for anonymous respondents
        );
    }

    @Transactional(readOnly = true)
    public SubmissionStatusResponse getStatus(UUID accessToken) {
        Submission submission = getSessionSubmission(accessToken);
        return new SubmissionStatusResponse(
                submission.getId(),
                submission.getStatus(),
                submission.getSubmittedAt(),
                submission.getEvaluatedAt()
        );
    }

    /**
     * Respondent-facing results. 404 until the evaluation has completed, and
     * always 404 when the link owner chose not to show results — the FE treats
     * 404 as "not available" without learning whether results exist.
     */
    @Transactional(readOnly = true)
    public MemberResultsResponse getResults(UUID accessToken) {
        Submission submission = getRespondentVisibleResultsSubmission(accessToken);
        return buildResults(submission);
    }

    @Transactional(readOnly = true)
    public PillarDetailResponse getResultsPillarDetail(UUID accessToken, UUID pillarId) {
        Submission submission = getRespondentVisibleResultsSubmission(accessToken);
        return memberResultsService.getPillarDetail(submission.getId(), pillarId);
    }

    // ---- Admin: link CRUD ----

    @Transactional(readOnly = true)
    public Page<PublicAssessmentLinkDto> listLinks(int page, int size) {
        return linkRepository.findAllByOrderByCreatedAtDesc(PageRequest.of(page, size))
                .map(this::toDto);
    }

    @Transactional
    public PublicAssessmentLinkDto createLink(CreatePublicAssessmentLinkRequest request,
                                              UUID createdBy) {
        Pipeline pipeline = pipelineRepository.findById(request.pipelineId())
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", request.pipelineId().toString()));
        if (pipeline.getStatus() != PipelineStatus.PUBLISHED) {
            throw new BadRequestException("Only PUBLISHED pipelines can be shared as a public assessment");
        }

        PublicAssessmentLink link = new PublicAssessmentLink();
        link.setToken(UUID.randomUUID());
        link.setPipeline(pipeline);
        link.setTitle(request.title().trim());
        link.setDescription(request.description());
        if (request.respondentEmailMode() != null) {
            link.setRespondentEmailMode(request.respondentEmailMode());
        }
        if (request.respondentNameMode() != null) {
            link.setRespondentNameMode(request.respondentNameMode());
        }
        if (request.genderMode() != null) {
            link.setGenderMode(request.genderMode());
        }
        if (request.showResultsToRespondent() != null) {
            link.setShowResultsToRespondent(request.showResultsToRespondent());
        }
        link.setMaxResponses(request.maxResponses());
        link.setCreatedBy(createdBy);

        link = linkRepository.save(link);
        log.info("Public assessment link {} created for pipeline {} by {}",
                link.getId(), pipeline.getId(), createdBy);
        return toDto(link);
    }

    @Transactional(readOnly = true)
    public PublicAssessmentLinkDto getLink(UUID linkId) {
        return toDto(loadLink(linkId));
    }

    @Transactional
    public PublicAssessmentLinkDto updateLink(UUID linkId, UpdatePublicAssessmentLinkRequest request) {
        PublicAssessmentLink link = loadLink(linkId);

        if (request.title() != null) {
            if (request.title().isBlank()) {
                throw new BadRequestException("Title must not be blank");
            }
            link.setTitle(request.title().trim());
        }
        if (request.description() != null) {
            link.setDescription(request.description());
        }
        if (request.status() != null) {
            link.setStatus(request.status());
        }
        if (request.respondentEmailMode() != null) {
            link.setRespondentEmailMode(request.respondentEmailMode());
        }
        if (request.respondentNameMode() != null) {
            link.setRespondentNameMode(request.respondentNameMode());
        }
        if (request.genderMode() != null) {
            link.setGenderMode(request.genderMode());
        }
        if (request.showResultsToRespondent() != null) {
            link.setShowResultsToRespondent(request.showResultsToRespondent());
        }
        if (request.maxResponses() != null) {
            link.setMaxResponses(request.maxResponses());
        }

        return toDto(linkRepository.save(link));
    }

    @Transactional
    public void deleteLink(UUID linkId) {
        PublicAssessmentLink link = loadLink(linkId);
        if (submissionRepository.countByPublicLinkId(linkId) > 0) {
            throw new IllegalOperationException(
                    "Cannot delete a public assessment that has responses. Archive it instead.");
        }
        linkRepository.delete(link);
        log.info("Public assessment link {} deleted", linkId);
    }

    // ---- Admin: responses ----

    @Transactional(readOnly = true)
    public Page<PublicSubmissionResponsePageDto> listResponses(UUID linkId, int page, int size) {
        loadLink(linkId); // 404 for unknown links rather than an empty page

        Page<Submission> submissions = submissionRepository
                .findByPublicLinkId(linkId, PageRequest.of(page, size));

        // Batched score lookup — one query for the whole page instead of a
        // per-row OverallSummary fetch.
        List<UUID> submissionIds = submissions.getContent().stream().map(Submission::getId).toList();
        Map<UUID, OverallSummary> summariesBySubmission = submissionIds.isEmpty()
                ? Map.of()
                : overallSummaryRepository.findBySubmissionIdIn(submissionIds).stream()
                        .collect(Collectors.toMap(s -> s.getSubmission().getId(), s -> s, (a, b) -> a));

        return submissions.map(sub -> {
            OverallSummary summary = summariesBySubmission.get(sub.getId());
            return new PublicSubmissionResponsePageDto(
                    sub.getId(),
                    sub.getStatus(),
                    sub.getRespondentEmail(),
                    sub.getRespondentName(),
                    sub.getStartedAt(),
                    sub.getSubmittedAt(),
                    sub.getEvaluatedAt(),
                    summary != null ? summary.getOverallScorePercentage() : null
            );
        });
    }

    /**
     * Full results for a single response — admin view, so the link's
     * {@code showResultsToRespondent} flag is intentionally ignored.
     */
    @Transactional(readOnly = true)
    public MemberResultsResponse getResponseResults(UUID linkId, UUID submissionId) {
        Submission submission = loadLinkSubmission(linkId, submissionId);
        // Mid-evaluation states have no (or stale) result rows — treat as 404
        // so the FE can poll, mirroring the member results gate.
        if (submission.getStatus() == SubmissionStatus.IN_PROGRESS
                || submission.getStatus() == SubmissionStatus.SUBMITTED) {
            throw new ResourceNotFoundException("Results", submissionId.toString());
        }
        return buildResults(submission);
    }

    @Transactional(readOnly = true)
    public PillarDetailResponse getResponsePillarDetail(UUID linkId, UUID submissionId, UUID pillarId) {
        loadLinkSubmission(linkId, submissionId);
        return memberResultsService.getPillarDetail(submissionId, pillarId);
    }

    /**
     * Hard-deletes one response. Dependent rows (answers, pillar_evaluations,
     * overall_summaries) are removed by DB-level ON DELETE CASCADE. The link's
     * {@code responseCount} is NOT decremented — the cap counts started
     * sessions, not surviving rows.
     */
    @Transactional
    public void deleteResponse(UUID linkId, UUID submissionId) {
        Submission submission = loadLinkSubmission(linkId, submissionId);
        submissionRepository.delete(submission);
        log.info("Public submission {} deleted from link {}", submissionId, linkId);
    }

    // ---- Private helpers ----

    private Submission getSessionSubmission(UUID accessToken) {
        return submissionRepository.findByAccessTokenWithPublicLink(accessToken)
                .orElseThrow(() -> new ResourceNotFoundException("Session", accessToken.toString()));
    }

    /** Session lookup + the respondent-results visibility gate (both → 404). */
    private Submission getRespondentVisibleResultsSubmission(UUID accessToken) {
        Submission submission = getSessionSubmission(accessToken);
        if (!submission.getPublicLink().isShowResultsToRespondent()
                || submission.getStatus() != SubmissionStatus.EVALUATED) {
            throw new ResourceNotFoundException("Results", submission.getId().toString());
        }
        return submission;
    }

    /** Loads a submission and verifies it belongs to the given public link (404 otherwise). */
    private Submission loadLinkSubmission(UUID linkId, UUID submissionId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        if (submission.getPublicLink() == null
                || !submission.getPublicLink().getId().equals(linkId)) {
            throw new ResourceNotFoundException("Submission", submissionId.toString());
        }
        return submission;
    }

    private PublicAssessmentLink loadLink(UUID linkId) {
        return linkRepository.findById(linkId)
                .orElseThrow(() -> new ResourceNotFoundException("Public assessment", linkId.toString()));
    }

    private String effectiveStatus(PublicAssessmentLink link) {
        boolean capReached = link.getMaxResponses() != null
                && link.getResponseCount() >= link.getMaxResponses();
        if (link.getStatus() == PublicAssessmentLinkStatus.ACTIVE && capReached) {
            return STATUS_MAX_RESPONSES_REACHED;
        }
        return link.getStatus().name();
    }

    private PublicAssessmentLinkDto toDto(PublicAssessmentLink link) {
        return new PublicAssessmentLinkDto(
                link.getId(),
                link.getToken(),
                link.getPipeline().getId(),
                link.getPipeline().getName(),
                link.getTitle(),
                link.getDescription(),
                link.getStatus(),
                link.getRespondentEmailMode(),
                link.getRespondentNameMode(),
                link.getGenderMode(),
                link.isShowResultsToRespondent(),
                link.getMaxResponses(),
                link.getResponseCount(),
                link.getCreatedAt()
        );
    }

    /**
     * Pillar/question tree in the member assessment shape. Pass an empty map
     * for the by-token landing payload (no answers yet) and the saved answers
     * for the session detail. The link's {@code genderMode} is applied to the
     * pipeline's system Gender question (hidden / optional / required) without
     * mutating the pipeline.
     */
    private List<AssessmentDetailResponse.PillarSection> buildPillarSections(
            Pipeline pipeline, Map<UUID, Answer> answersByQuestion, RespondentFieldMode genderMode) {
        return pipeline.getPillars().stream()
                .sorted(Comparator.comparingInt(Pillar::getDisplayOrder))
                .map(pillar -> {
                    List<AssessmentDetailResponse.QuestionWithAnswer> questionsWithAnswers =
                            pillar.getQuestions().stream()
                                    .sorted(Comparator.comparingInt(Question::getDisplayOrder))
                                    .filter(q -> !(isGenderQuestion(q) && genderMode == RespondentFieldMode.NONE))
                                    .map(q -> {
                                        Answer answer = answersByQuestion.get(q.getId());
                                        AssessmentDetailResponse.AnswerData answerData = answer != null
                                                ? new AssessmentDetailResponse.AnswerData(
                                                        answer.getId(),
                                                        answer.getResponseText(),
                                                        answer.getSelectedValue())
                                                : null;

                                        return new AssessmentDetailResponse.QuestionWithAnswer(
                                                q.getId(),
                                                q.getType(),
                                                q.getPromptText(),
                                                q.getDisplayOrder(),
                                                requiredForLink(q, genderMode),
                                                q.getConfigJson(),
                                                answerData
                                        );
                                    })
                                    .toList();

                    return new AssessmentDetailResponse.PillarSection(
                            pillar.getId(),
                            pillar.getName(),
                            pillar.getDescription(),
                            pillar.getIconKey(),
                            pillar.getDisplayOrder(),
                            pillar.getType().name(),
                            questionsWithAnswers
                    );
                })
                .toList();
    }

    private ReviewResponse buildReview(Submission submission) {
        Pipeline pipeline = submission.getPublicLink().getPipeline();
        List<Answer> answers = answerRepository.findBySubmissionId(submission.getId());

        Set<UUID> answeredQuestionIds = answers.stream()
                .filter(this::hasContent)
                .map(a -> a.getQuestion().getId())
                .collect(Collectors.toSet());

        RespondentFieldMode genderMode = submission.getPublicLink().getGenderMode();
        List<Question> requiredQuestions = pipeline.getPillars().stream()
                .flatMap(p -> p.getQuestions().stream())
                .filter(q -> requiredForLink(q, genderMode))
                .toList();

        List<ReviewResponse.UnansweredQuestion> unanswered = requiredQuestions.stream()
                .filter(q -> !answeredQuestionIds.contains(q.getId()))
                .map(q -> new ReviewResponse.UnansweredQuestion(
                        q.getId(),
                        q.getPromptText(),
                        q.getPillar().getId(),
                        q.getPillar().getName()))
                .toList();

        int totalRequired = requiredQuestions.size();
        int answeredRequired = totalRequired - unanswered.size();

        return new ReviewResponse(
                submission.getId(),
                unanswered.isEmpty(),
                totalRequired,
                answeredRequired,
                unanswered
        );
    }

    /**
     * Public submissions are editable only while IN_PROGRESS — there is no
     * deadline and no admin re-edit flow for anonymous sessions.
     */
    private void validateEditable(Submission submission) {
        if (submission.getStatus() != SubmissionStatus.IN_PROGRESS) {
            throw new BadRequestException("Assessment has already submitted and cannot be modified");
        }
    }

    /**
     * Reject answers for questions outside this link's pipeline — without this,
     * the Answer FK only checks that the questionId exists somewhere.
     */
    private void requireQuestionInPipeline(Submission submission, UUID questionId) {
        boolean belongs = submission.getPublicLink().getPipeline().getPillars().stream()
                .flatMap(pillar -> pillar.getQuestions().stream())
                .anyMatch(q -> q.getId().equals(questionId));
        if (!belongs) {
            throw new ResourceNotFoundException("Question", questionId.toString());
        }
    }

    private AnswerResponse saveAnswerInternal(Submission submission, UUID questionId,
                                              SaveAnswerRequest request) {
        requireQuestionInPipeline(submission, questionId);
        Answer answer = answerRepository.findBySubmissionIdAndQuestionId(submission.getId(), questionId)
                .orElseGet(() -> {
                    Answer newAnswer = new Answer();
                    newAnswer.setSubmission(submission);
                    Question questionRef = new Question();
                    questionRef.setId(questionId);
                    newAnswer.setQuestion(questionRef);
                    return newAnswer;
                });

        answer.setResponseText(request.responseText());
        answer.setSelectedValue(request.selectedValue());
        answer = answerRepository.save(answer);

        return new AnswerResponse(
                answer.getId(),
                questionId,
                answer.getResponseText(),
                answer.getSelectedValue(),
                answer.getUpdatedAt()
        );
    }

    /**
     * Builds the member-shaped results payload straight from the evaluation
     * rows. Bypasses {@code MemberResultsService.getCachedResults} (it
     * dereferences the assignment); the row→DTO mappings are shared via
     * {@link MemberResultsService#toPillarScores} and
     * {@link MemberResultsService#getPillarDetail}. Survey/post-completion
     * fields are always null and premium features always on — public
     * submissions have no org tier and no paired survey flow.
     */
    private MemberResultsResponse buildResults(Submission submission) {
        UUID submissionId = submission.getId();
        OverallSummary summary = overallSummaryRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Results", submissionId.toString()));

        List<PillarScoreSummary> pillarScores = memberResultsService
                .toPillarScores(pillarEvaluationRepository.findBySubmissionId(submissionId));
        List<PersonalInfoEntry> personalInfo = personalInfoResolver.resolve(submissionId);

        return new MemberResultsResponse(
                submissionId,
                submission.getPublicLink().getPipeline().getName(),
                summary.getOverallScorePercentage(),
                summary.getSummaryNarrative(),
                summary.getStrengths(),
                summary.getDevelopmentAreas(),
                pillarScores,
                true, // no org tier — public submissions always get the premium report
                submission.getEvaluatedAt(),
                null, null, null, null, // free-tier fields
                summary.getCorePattern(),
                summary.getMovingForwardNarrative(),
                null, // postCompletion
                null, // surveyResponse
                null, // survey
                personalInfo
        );
    }

    private void validateFieldMode(RespondentFieldMode mode, String value, String fieldLabel) {
        String normalized = normalize(value);
        if (mode == RespondentFieldMode.REQUIRED && normalized == null) {
            throw new BadRequestException("This assessment requires a respondent " + fieldLabel);
        }
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private boolean hasContent(Answer answer) {
        return (answer.getResponseText() != null && !answer.getResponseText().isBlank())
                || (answer.getSelectedValue() != null && !answer.getSelectedValue().isBlank());
    }

    private static boolean isGenderQuestion(Question q) {
        return SystemQuestion.GENDER.equals(q.getSystemKey());
    }

    /**
     * Effective "required" for a question when taken through this link: the
     * system Gender question follows the link's {@code genderMode} (REQUIRED →
     * required, OPTIONAL/NONE → not), everything else keeps the pipeline value.
     */
    private static boolean requiredForLink(Question q, RespondentFieldMode genderMode) {
        if (isGenderQuestion(q)) {
            return genderMode == RespondentFieldMode.REQUIRED;
        }
        return q.isRequired();
    }

    /**
     * Merge function for de-duplicating answers keyed by question id — same
     * defensive policy as the member assessment flow: keep the most recently
     * updated answer rather than letting the stream collector throw.
     */
    private static Answer mostRecentAnswer(Answer existing, Answer replacement) {
        return replacement.getUpdatedAt().isAfter(existing.getUpdatedAt())
                ? replacement
                : existing;
    }
}
