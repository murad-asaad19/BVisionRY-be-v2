package com.bvisionry.survey.service;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.tx.AfterCommit;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.publicassessment.entity.PublicAssessmentLink;
import com.bvisionry.publicassessment.entity.PublicAssessmentLinkStatus;
import com.bvisionry.publicassessment.repository.PublicAssessmentLinkRepository;
import com.bvisionry.reporting.service.CacheInvalidationService;
import com.bvisionry.survey.dto.MemberSurveyDto;
import com.bvisionry.survey.dto.PublicSurveyDto;
import com.bvisionry.survey.dto.SurveyAnswerSubmitDto;
import com.bvisionry.survey.dto.SurveySubmitRequest;
import com.bvisionry.survey.dto.SurveySubmitResponseDto;
import com.bvisionry.survey.entity.RespondentFieldMode;
import com.bvisionry.survey.entity.ResponseSource;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyAnswer;
import com.bvisionry.survey.entity.SurveyQuestion;
import com.bvisionry.survey.entity.SurveyQuestionType;
import com.bvisionry.survey.entity.SurveyResponse;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.entity.SurveyVisibility;
import com.bvisionry.survey.repository.SurveyRepository;
import com.bvisionry.survey.repository.SurveyResponseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SurveyResponseService {

    private static final int MAX_USER_AGENT_LENGTH = 255;

    private final SurveyRepository surveyRepository;
    private final SurveyResponseRepository responseRepository;
    private final SurveyMapper mapper;
    private final SubmissionRepository submissionRepository;
    private final UserRepository userRepository;
    private final CacheInvalidationService cacheInvalidationService;
    private final AuditService auditService;
    private final PublicAssessmentLinkRepository publicAssessmentLinkRepository;
    private final EmailService emailService;
    private final FrontendUrls frontendUrls;

    @Transactional(readOnly = true)
    public PublicSurveyDto getPublicByToken(UUID token) {
        Survey survey = loadPubliclyReachableOrThrow(token);
        if (survey.getStatus() == SurveyStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.GONE, "Survey is closed");
        }
        if (survey.getStatus() != SurveyStatus.PUBLISHED) {
            throw new ResourceNotFoundException("Survey", token.toString());
        }
        return mapper.toPublicSurveyDto(survey);
    }

    @Transactional
    public SurveySubmitResponseDto submitPublic(UUID token,
                                                SurveySubmitRequest request,
                                                String ipHash,
                                                String cookieId,
                                                String userAgent) {
        Survey survey = loadPubliclyReachableOrThrow(token);
        if (survey.getStatus() == SurveyStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.GONE, "Survey is closed");
        }
        if (survey.getStatus() != SurveyStatus.PUBLISHED) {
            throw new ResourceNotFoundException("Survey", token.toString());
        }

        validateFieldMode(survey.getRespondentEmailMode(), request.respondentEmail(), "email");
        validateFieldMode(survey.getRespondentNameMode(), request.respondentName(), "name");

        // Resolve the gift (and mint its per-response token) before persisting so
        // the token is saved on the response row — that token is what later ties
        // the gifted submission back to THIS response instead of matching by email.
        GiftAssessment gift = planGiftAssessment(survey, request.respondentEmail());

        SurveySubmitResponseDto result = persistResponse(
                survey,
                request.answers(),
                new ResponseContext.Public(
                        request.respondentEmail(),
                        request.respondentName(),
                        ipHash,
                        cookieId,
                        gift == null ? null : gift.giftToken()),
                userAgent);

        if (gift == null) {
            return result;
        }

        // Send runs after commit (so a rolled-back submission never emails) and
        // async (so SMTP latency can't block the response). The email may still
        // fail silently, so we also return the link to the client as a fallback.
        String email = normalize(request.respondentEmail());
        String name = normalize(request.respondentName());
        String surveyName = survey.getName();
        String assessmentTitle = gift.link().getTitle();
        String assessmentUrl = gift.assessmentUrl();
        AfterCommit.run(() -> emailService.sendSurveyGiftAssessmentAsync(
                email, name, surveyName, assessmentTitle, assessmentUrl));
        return new SurveySubmitResponseDto(
                result.responseId(), result.thankYouMessage(), assessmentUrl);
    }

    /**
     * Resolves whether this public submission should receive a gifted assessment
     * and, if so, mints the per-response gift token and builds its link. Returns
     * {@code null} when no gift applies — no gift configured, no email collected,
     * email mode NONE, or the link is missing/inactive. The email-mode guard
     * mirrors the config-time invariant so a crafted submit can't trigger a gift
     * on a survey that hides the email field.
     *
     * <p>The link carries the token as {@code ?g=<giftToken>} so that when the
     * respondent opens it, {@code PublicAssessmentService} can link the resulting
     * submission back to this exact response.
     */
    private GiftAssessment planGiftAssessment(Survey survey, String respondentEmail) {
        UUID giftLinkId = survey.getGiftPublicAssessmentLinkId();
        String email = normalize(respondentEmail);
        if (giftLinkId == null || email == null
                || survey.getRespondentEmailMode() == RespondentFieldMode.NONE) {
            return null;
        }
        PublicAssessmentLink link = publicAssessmentLinkRepository.findById(giftLinkId).orElse(null);
        if (link == null || link.getStatus() != PublicAssessmentLinkStatus.ACTIVE) {
            return null;
        }
        UUID giftToken = UUID.randomUUID();
        String assessmentUrl = frontendUrls.assessmentLink(link.getToken()) + "?g=" + giftToken;
        return new GiftAssessment(link, giftToken, assessmentUrl);
    }

    /** A resolved gift: the target link, the per-response token, and the built link URL. */
    private record GiftAssessment(PublicAssessmentLink link, UUID giftToken, String assessmentUrl) {}

    /**
     * Resolve the published survey paired to this user's assessment for the
     * authenticated post-assessment flow. Returns 404 if the submission does
     * not exist, the caller does not own it, or no published survey is paired
     * to the pipeline. The 404 is intentional — leaking 403 vs 404 here would
     * disclose whether a submissionId belongs to another user. Returns 409
     * once a response exists, so clients can show the already-submitted state
     * on load instead of letting the user refill a form the submit will reject.
     */
    @Transactional(readOnly = true)
    public MemberSurveyDto getForSubmission(UUID submissionId, UUID currentUserId) {
        Survey survey = resolveSurveyForSubmission(submissionId, currentUserId);
        if (responseRepository.existsBySurveyIdAndSubmissionId(survey.getId(), submissionId)) {
            throw new DuplicateResourceException(
                    "Survey response already submitted for this assessment");
        }
        return mapper.toMemberSurveyDto(survey);
    }

    /**
     * Submit the post-assessment survey response for an authenticated user.
     * Enforces single-submission per assessment; respondent identity is taken
     * from the current user, not the request body.
     *
     * <p>Like {@link #getForSubmission}, missing/foreign submissions surface as
     * 404 — never 403. Returning a distinct 403 on a submission the caller
     * doesn't own would leak that the id exists, so we treat both cases
     * identically.
     */
    @Transactional
    public SurveySubmitResponseDto submitForSubmission(UUID submissionId,
                                                        UUID currentUserId,
                                                        SurveySubmitRequest request,
                                                        String userAgent) {
        Submission submission = loadOwnedSubmission(submissionId, currentUserId);
        Survey survey = resolveSurveyFromPipeline(submission);

        if (responseRepository.existsBySurveyIdAndSubmissionId(survey.getId(), submissionId)) {
            throw new DuplicateResourceException(
                    "Survey response already submitted for this assessment");
        }

        User user = userRepository.findById(currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("User", currentUserId.toString()));

        SurveySubmitResponseDto result;
        try {
            result = persistResponse(
                    survey,
                    request.answers(),
                    new ResponseContext.Member(submission, user),
                    userAgent);
        } catch (DataIntegrityViolationException e) {
            // The exists-check above is racy under concurrent submits; the unique
            // partial index ux_survey_responses_survey_submission is the real
            // gate. Translate the constraint violation into the same domain
            // exception so the caller sees one consistent error.
            throw new DuplicateResourceException(
                    "Survey response already submitted for this assessment");
        }

        // Defensive: a submission without an assignment (public/anonymous flow)
        // has no org to attribute the audit entry to — skip it.
        if (submission.getAssignment() != null) {
            auditService.log(currentUserId, submission.getAssignment().getOrganization().getId(),
                    OrgAuditActions.SURVEY_RESPONSE_SUBMITTED,
                    OrgAuditActions.ENTITY_SURVEY, survey.getId(),
                    Map.of("surveyName", survey.getName(),
                           "submissionId", submissionId.toString()));
        }

        AfterCommit.run(() -> cacheInvalidationService.invalidateMemberResultsForSubmission(submissionId));
        return result;
    }

    /**
     * Belt-and-suspenders gate for the public-by-token path. A PRIVATE survey must
     * be unreachable through this endpoint even if a stale {@code publicToken}
     * somehow exists — we return the same 404 as the unknown-token case so an
     * attacker can't tell whether a token corresponds to an existing-but-private
     * survey.
     */
    private Survey loadPubliclyReachableOrThrow(UUID token) {
        Survey survey = surveyRepository.findByPublicToken(token)
                .orElseThrow(() -> new ResourceNotFoundException("Survey", token.toString()));
        if (survey.getVisibility() != SurveyVisibility.PUBLIC) {
            throw new ResourceNotFoundException("Survey", token.toString());
        }
        return survey;
    }

    private Survey resolveSurveyForSubmission(UUID submissionId, UUID currentUserId) {
        Submission submission = loadOwnedSubmission(submissionId, currentUserId);
        return resolveSurveyFromPipeline(submission);
    }

    private Submission loadOwnedSubmission(UUID submissionId, UUID currentUserId) {
        Submission submission = submissionRepository.findByIdWithAssignmentAndPipeline(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        // Public (anonymous) submissions have no owning user — treat them like
        // any other foreign submission (404, never reveal existence).
        if (submission.getUser() == null || !submission.getUser().getId().equals(currentUserId)) {
            throw new ResourceNotFoundException("Submission", submissionId.toString());
        }
        return submission;
    }

    private Survey resolveSurveyFromPipeline(Submission submission) {
        Pipeline pipeline = submission.getAssignment().getPipeline();
        UUID surveyId = pipeline.getPostCompletionSurveyId();
        if (surveyId == null) {
            throw new ResourceNotFoundException("Survey", "post-completion (none paired)");
        }
        Survey survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("Survey", surveyId.toString()));
        if (survey.getStatus() != SurveyStatus.PUBLISHED) {
            throw new ResourceNotFoundException("Survey", surveyId.toString());
        }
        return survey;
    }

    private SurveySubmitResponseDto persistResponse(Survey survey,
                                                     List<SurveyAnswerSubmitDto> submittedAnswers,
                                                     ResponseContext context,
                                                     String userAgent) {
        Map<UUID, SurveyQuestion> questionIndex = new HashMap<>();
        for (var pillar : survey.getPillars()) {
            for (var q : pillar.getQuestions()) {
                questionIndex.put(q.getId(), q);
            }
        }

        Map<UUID, SurveyAnswerSubmitDto> byId = new HashMap<>();
        for (SurveyAnswerSubmitDto a : submittedAnswers) {
            if (a.questionId() == null) {
                throw new BadRequestException("Each answer must include questionId");
            }
            if (!questionIndex.containsKey(a.questionId())) {
                throw new BadRequestException("Unknown questionId: " + a.questionId());
            }
            byId.put(a.questionId(), a);
        }

        for (SurveyQuestion q : questionIndex.values()) {
            if (q.isRequired() && !answerHasContent(byId.get(q.getId()))) {
                throw new BadRequestException("Missing required answer for question: " + q.getPromptText());
            }
        }

        SurveyResponse response = new SurveyResponse();
        response.setSurvey(survey);
        response.setSubmittedAt(Instant.now());
        response.setUserAgent(truncate(userAgent));
        applyContext(response, context);

        List<SurveyAnswer> entities = new ArrayList<>();
        for (Map.Entry<UUID, SurveyAnswerSubmitDto> entry : byId.entrySet()) {
            SurveyQuestion q = questionIndex.get(entry.getKey());
            SurveyAnswerSubmitDto dto = entry.getValue();
            SurveyAnswer answer = buildAnswer(q, dto);
            answer.setResponse(response);
            entities.add(answer);
        }
        response.setAnswers(entities);

        SurveyResponse saved = responseRepository.save(response);
        return new SurveySubmitResponseDto(saved.getId(), "Thank you for your response.", null);
    }

    private SurveyAnswer buildAnswer(SurveyQuestion question, SurveyAnswerSubmitDto dto) {
        SurveyAnswer answer = new SurveyAnswer();
        answer.setQuestion(question);

        switch (question.getType()) {
            case SHORT_TEXT -> {
                String text = dto.responseText();
                validateTextLength(question, text);
                answer.setResponseText(text);
            }
            case MULTIPLE_CHOICE -> {
                boolean multiSelect = isMultiSelect(question);
                if (multiSelect) {
                    List<String> values = dto.selectedValues();
                    if (values != null) {
                        for (String v : values) {
                            validateChoiceOption(question, v);
                        }
                    }
                    answer.setSelectedValues(values);
                } else {
                    String selected = dto.selectedValue();
                    if (selected != null) {
                        validateChoiceOption(question, selected);
                    }
                    answer.setSelectedValue(selected);
                }
            }
            case LIKERT -> {
                String selected = dto.selectedValue();
                answer.setSelectedValue(selected);
                if (selected != null && !selected.isBlank()) {
                    try {
                        int val = Integer.parseInt(selected);
                        if (val < 1 || val > 5) {
                            throw new BadRequestException("LIKERT answer must be between 1 and 5 for question: "
                                    + question.getPromptText());
                        }
                        answer.setNumericValue(BigDecimal.valueOf(val));
                    } catch (NumberFormatException e) {
                        throw new BadRequestException("LIKERT answer must be numeric for question: "
                                + question.getPromptText());
                    }
                }
            }
            case NUMBER -> {
                String selected = dto.selectedValue();
                answer.setSelectedValue(selected);
                if (selected != null && !selected.isBlank()) {
                    try {
                        BigDecimal val = new BigDecimal(selected);
                        answer.setNumericValue(val);
                    } catch (NumberFormatException e) {
                        throw new BadRequestException("NUMBER answer must be numeric for question: "
                                + question.getPromptText());
                    }
                }
            }
            case SELF_RATING -> {
                String selected = dto.selectedValue();
                answer.setSelectedValue(selected);
                if (selected != null && !selected.isBlank()) {
                    try {
                        BigDecimal val = new BigDecimal(selected);
                        validateSelfRatingRange(question, val);
                        answer.setNumericValue(val);
                    } catch (NumberFormatException e) {
                        throw new BadRequestException("SELF_RATING answer must be numeric for question: "
                                + question.getPromptText());
                    }
                }
            }
        }
        return answer;
    }

    /**
     * Stamp the {@link ResponseContext} variant onto the entity. Sealing the
     * type lets us branch exhaustively without nullable parameter passing —
     * each variant fills only the columns that are meaningful for its flow.
     */
    private void applyContext(SurveyResponse response, ResponseContext context) {
        switch (context) {
            case ResponseContext.Public p -> {
                response.setSource(ResponseSource.PUBLIC_LINK);
                response.setSubmission(null);
                response.setRespondentUserId(null);
                response.setRespondentEmail(normalize(p.email()));
                response.setRespondentName(normalize(p.name()));
                response.setIpHash(p.ipHash());
                response.setCookieId(p.cookieId());
                response.setGiftToken(p.giftToken());
            }
            case ResponseContext.Member m -> {
                response.setSource(ResponseSource.POST_ASSESSMENT);
                response.setSubmission(m.submission());
                response.setRespondentUserId(m.user().getId());
                response.setRespondentEmail(normalize(m.user().getEmail()));
                response.setRespondentName(normalize(m.user().getName()));
                response.setIpHash(null);
                response.setCookieId(null);
            }
        }
    }

    private void validateSelfRatingRange(SurveyQuestion question, BigDecimal val) {
        if (question.getConfigJson() == null) return;
        Object minRaw = question.getConfigJson().get("min");
        Object maxRaw = question.getConfigJson().get("max");
        if (minRaw instanceof Number min
                && val.compareTo(BigDecimal.valueOf(min.doubleValue())) < 0) {
            throw new BadRequestException(
                    "Value below minimum for question: " + question.getPromptText());
        }
        if (maxRaw instanceof Number max
                && val.compareTo(BigDecimal.valueOf(max.doubleValue())) > 0) {
            throw new BadRequestException(
                    "Value above maximum for question: " + question.getPromptText());
        }
    }

    private void validateTextLength(SurveyQuestion question, String text) {
        if (text == null) return;
        Map<String, Object> config = question.getConfigJson();
        if (config == null) return;
        Object min = config.get("minLength");
        Object max = config.get("maxLength");
        if (max instanceof Number n && text.length() > n.intValue()) {
            throw new BadRequestException("Response exceeds max length for question: "
                    + question.getPromptText());
        }
        if (min instanceof Number n && text.length() < n.intValue() && !text.isBlank()) {
            throw new BadRequestException("Response is shorter than minimum length for question: "
                    + question.getPromptText());
        }
    }

    private boolean isMultiSelect(SurveyQuestion question) {
        if (question.getConfigJson() == null) return false;
        Object raw = question.getConfigJson().get("multiSelect");
        return raw instanceof Boolean b && b;
    }

    private void validateChoiceOption(SurveyQuestion question, String value) {
        if (question.getConfigJson() == null) return;
        Object raw = question.getConfigJson().get("options");
        if (raw instanceof List<?> list) {
            boolean ok = list.stream().anyMatch(o -> o instanceof String s && s.equals(value));
            if (!ok) {
                throw new BadRequestException("Invalid option '" + value + "' for question: "
                        + question.getPromptText());
            }
        }
    }

    private boolean answerHasContent(SurveyAnswerSubmitDto dto) {
        if (dto == null) return false;
        boolean textContent = dto.responseText() != null && !dto.responseText().isBlank();
        boolean singleContent = dto.selectedValue() != null && !dto.selectedValue().isBlank();
        boolean multiContent = dto.selectedValues() != null && !dto.selectedValues().isEmpty();
        return textContent || singleContent || multiContent;
    }

    private void validateFieldMode(RespondentFieldMode mode, String value, String fieldLabel) {
        String normalized = normalize(value);
        if (mode == RespondentFieldMode.REQUIRED
                && (normalized == null || normalized.isBlank())) {
            throw new BadRequestException("This survey requires a respondent " + fieldLabel);
        }
    }

    private String normalize(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }

    private String truncate(String ua) {
        if (ua == null) return null;
        return ua.length() > MAX_USER_AGENT_LENGTH ? ua.substring(0, MAX_USER_AGENT_LENGTH) : ua;
    }
}
