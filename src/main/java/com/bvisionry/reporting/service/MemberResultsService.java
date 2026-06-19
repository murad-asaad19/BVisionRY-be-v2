package com.bvisionry.reporting.service;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.config.CacheConfig;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.pipeline.service.PostCompletionLinkResolver;
import com.bvisionry.reporting.dto.MemberHistoryResponse;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PersonalInfoEntry;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import com.bvisionry.reporting.dto.PillarScoreSummary;
import com.bvisionry.survey.dto.SubmissionSurveyResponseDto;
import com.bvisionry.survey.dto.SurveySummary;
import com.bvisionry.survey.entity.SurveyResponse;
import com.bvisionry.survey.repository.SurveyResponseRepository;
import com.bvisionry.survey.service.SurveyResultsService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.context.annotation.Lazy;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class MemberResultsService {

    private final SubmissionRepository submissionRepository;
    private final PillarEvaluationRepository pillarEvaluationRepository;
    private final OverallSummaryRepository overallSummaryRepository;
    private final PremiumFeatureGuard premiumFeatureGuard;
    private final PostCompletionLinkResolver postCompletionLinkResolver;
    private final SurveyResponseRepository surveyResponseRepository;
    private final SurveyResultsService surveyResultsService;
    private final PersonalInfoResolver personalInfoResolver;

    // Self-reference so the @Cacheable method goes through the Spring proxy
    // when invoked from the sibling public method in this same bean.
    @Autowired
    @Lazy
    private MemberResultsService self;

    /**
     * Get results for a completed submission. The heavy lookup is cached;
     * viewer-dependent fields (premium flag, super-admin-only payloads) are
     * computed fresh each call so a Super Admin (or a subsequent org tier
     * change) cannot poison the cache.
     *
     * <p>Treated as 404 while a submission is mid-evaluation
     * ({@code SUBMITTED}). The frontend treats 404 as "still evaluating" and
     * polls — without this gate, a re-submitted partial re-eval would render
     * the stale prior summary because pillar_evaluations and overall_summary
     * remain in place until the eval transaction commits. We deliberately
     * still serve {@code FAILED} (last good results) and
     * {@code PENDING_REEDIT} (admin reopened pillars but member hasn't
     * re-submitted yet) so the user can see their prior results in those
     * states.
     */
    public MemberResultsResponse getResults(UUID submissionId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));
        if (submission.getStatus() == SubmissionStatus.IN_PROGRESS
                || submission.getStatus() == SubmissionStatus.SUBMITTED) {
            throw new ResourceNotFoundException("Results", submissionId.toString());
        }
        CachedMemberResults cached = self.getCachedResults(submissionId);
        boolean isPremium = premiumFeatureGuard.isPremiumOrSuperAdmin(cached.organizationId());
        boolean isSuperAdmin = SecurityUtils.isSuperAdmin();
        return applyViewerScope(cached.response(), isPremium, isSuperAdmin);
    }

    /**
     * Cache-backed fetch of the tier-agnostic payload. premiumFeaturesAvailable
     * on the inner response is always false here -- callers must set it via
     * {@link #applyViewerScope}. Do not call this from outside getResults().
     */
    @Cacheable(value = CacheConfig.MEMBER_RESULTS, key = "#submissionId")
    public CachedMemberResults getCachedResults(UUID submissionId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));

        UUID orgId = submission.getAssignment().getOrganization().getId();

        List<PillarEvaluation> evaluations = pillarEvaluationRepository.findBySubmissionId(submissionId);
        OverallSummary summary = overallSummaryRepository.findBySubmissionId(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("OverallSummary", submissionId.toString()));

        List<PillarScoreSummary> pillarScores = toPillarScores(evaluations);

        Optional<SurveyResponse> latestSurvey =
                surveyResponseRepository.findFirstBySubmissionIdOrderBySubmittedAtDesc(submissionId);
        SubmissionSurveyResponseDto surveyResponseDto = latestSurvey
                .map(r -> new SubmissionSurveyResponseDto(
                        r.getId(),
                        r.getSurvey().getId(),
                        r.getSurvey().getName(),
                        r.getSubmittedAt(),
                        surveyResultsService.buildAnswerDetailsForResponse(r.getId())))
                .orElse(null);
        UUID pairedSurveyId = submission.getAssignment().getPipeline().getPostCompletionSurveyId();
        SurveySummary surveySummary = pairedSurveyId != null
                ? new SurveySummary(
                        pairedSurveyId,
                        latestSurvey.map(SurveyResponse::getId).orElse(null),
                        latestSurvey.map(SurveyResponse::getSubmittedAt).orElse(null))
                : null;

        List<PersonalInfoEntry> personalInfo = personalInfoResolver.resolve(submissionId);

        MemberResultsResponse response = new MemberResultsResponse(
                submissionId,
                submission.getAssignment().getPipeline().getName(),
                summary.getOverallScorePercentage(),
                summary.getSummaryNarrative(),
                summary.getStrengths(),
                summary.getDevelopmentAreas(),
                pillarScores,
                false, // premiumFeaturesAvailable placeholder; real value set in getResults()
                submission.getEvaluatedAt(),
                null, null, null, null,
                summary.getCorePattern(),
                summary.getMovingForwardNarrative(),
                postCompletionLinkResolver
                        .resolveForCompletionEmail(submission.getAssignment().getPipeline(), submissionId)
                        .orElse(null),
                surveyResponseDto,
                surveySummary,
                personalInfo
        );
        return new CachedMemberResults(response, orgId);
    }

    /**
     * Re-wraps the cached payload for a specific viewer. Two orthogonal
     * filters apply:
     * <ul>
     *   <li>Free-tier callers (member or org admin) get {@code strengths} and
     *       {@code developmentAreas} blanked — those narrative blocks are part
     *       of the premium report and must not leak through the API even though
     *       the cached payload always carries them.</li>
     *   <li>Non-super-admin callers get {@code surveyResponse} and
     *       {@code survey} stripped — only the platform's super admin
     *       (Conductor) is allowed to read post-assessment survey responses;
     *       org admins and members must see no trace of them.</li>
     * </ul>
     */
    private MemberResultsResponse applyViewerScope(MemberResultsResponse r,
                                                   boolean premium,
                                                   boolean superAdmin) {
        return new MemberResultsResponse(
                r.submissionId(), r.pipelineName(), r.overallScore(), r.summaryNarrative(),
                premium ? r.strengths() : List.of(),
                premium ? r.developmentAreas() : List.of(),
                r.pillarScores(),
                premium, r.evaluatedAt(), r.freeTierSummary(), r.topStrengths(),
                r.maturityIndication(), r.premiumTeaser(), r.corePattern(),
                r.movingForwardNarrative(), r.postCompletion(),
                superAdmin ? r.surveyResponse() : null,
                superAdmin ? r.survey() : null,
                r.personalInfo()
        );
    }

    /**
     * Maps pillar evaluation rows to the score-summary list used on the
     * results overview. Extracted from {@link #getCachedResults} so the
     * public-assessment results path (which has no assignment/org to hang the
     * cached lookup on) can reuse the exact same row→DTO mapping.
     */
    public List<PillarScoreSummary> toPillarScores(List<PillarEvaluation> evaluations) {
        return evaluations.stream()
                .map(eval -> new PillarScoreSummary(
                        eval.getPillar().getId(),
                        eval.getPillar().getName(),
                        eval.getPillar().getIconKey(),
                        eval.getScorePercentage(),
                        eval.getMaturityLabel()
                ))
                .toList();
    }

    /**
     * Get detailed pillar evaluation -- Premium only (guard checked in controller).
     */
    public PillarDetailResponse getPillarDetail(UUID submissionId, UUID pillarId) {
        List<PillarEvaluation> evaluations = pillarEvaluationRepository
                .findBySubmissionIdAndPillarId(submissionId, pillarId);

        if (evaluations.isEmpty()) {
            throw new ResourceNotFoundException("PillarEvaluation",
                    "submission=" + submissionId + ",pillar=" + pillarId);
        }

        return toPillarDetail(evaluations.getFirst());
    }

    public Map<UUID, PillarDetailResponse> getAllPillarDetails(UUID submissionId) {
        return pillarEvaluationRepository.findBySubmissionId(submissionId).stream()
                .collect(Collectors.toMap(
                        e -> e.getPillar().getId(),
                        this::toPillarDetail,
                        (a, b) -> a));
    }

    private PillarDetailResponse toPillarDetail(PillarEvaluation eval) {
        return new PillarDetailResponse(
                eval.getPillar().getId(),
                eval.getPillar().getName(),
                eval.getPillar().getIconKey(),
                eval.getScorePercentage(),
                eval.getMaturityLabel(),
                eval.getAiScoreMeans(),
                eval.getAiWhatsWorking(),
                eval.getAiWhatCanImprove(),
                eval.getAiBusinessRelevance(),
                eval.getSelfAssessmentGap(),
                eval.isAiFailed()
        );
    }

    /**
     * Get submission history grouped by pipeline.
     */
    @Cacheable(value = CacheConfig.MEMBER_HISTORY, key = "#userId")
    public MemberHistoryResponse getHistory(UUID userId) {
        List<OverallSummary> summaries = overallSummaryRepository
                .findByUserIdOrderByGeneratedAtDesc(userId);

        Map<UUID, List<OverallSummary>> byPipeline = summaries.stream()
                .collect(Collectors.groupingBy(
                        s -> s.getSubmission().getAssignment().getPipeline().getId()));

        List<MemberHistoryResponse.PipelineHistory> pipelines = byPipeline.entrySet().stream()
                .map(entry -> {
                    List<OverallSummary> pipelineSummaries = entry.getValue();
                    String pipelineName = pipelineSummaries.getFirst()
                            .getSubmission().getAssignment().getPipeline().getName();

                    List<MemberHistoryResponse.SubmissionSummary> submissions = pipelineSummaries.stream()
                            .map(s -> new MemberHistoryResponse.SubmissionSummary(
                                    s.getSubmission().getId(),
                                    s.getOverallScorePercentage(),
                                    s.getSubmission().getStatus(),
                                    s.getSubmission().getEvaluatedAt()
                            ))
                            .toList();

                    return new MemberHistoryResponse.PipelineHistory(
                            entry.getKey(), pipelineName, submissions);
                })
                .toList();

        return new MemberHistoryResponse(pipelines);
    }
}
