package com.bvisionry.reporting.dto;

import com.bvisionry.pipeline.dto.PostCompletionLinkDto;
import com.bvisionry.survey.dto.SubmissionSurveyResponseDto;
import com.bvisionry.survey.dto.SurveySummary;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

public record MemberResultsResponse(
        UUID submissionId,
        String pipelineName,
        BigDecimal overallScore,
        String summaryNarrative,
        List<String> strengths,
        List<String> developmentAreas,
        List<PillarScoreSummary> pillarScores,
        boolean premiumFeaturesAvailable,
        Instant evaluatedAt,
        // Free tier fields (null for Premium)
        String freeTierSummary,
        List<String> topStrengths,
        String maturityIndication,
        String premiumTeaser,
        // Cross-pillar analysis fields
        String corePattern,
        String movingForwardNarrative,
        PostCompletionLinkDto postCompletion,
        // Embedded post-assessment survey answers (when the member has
        // submitted them). Different shape from `survey` below: this carries
        // the full answer payload for inline rendering on the results page.
        SubmissionSurveyResponseDto surveyResponse,
        // Pairing summary: null = no survey paired; non-null = paired
        // (with optional responseId/submittedAt set once the member submits).
        // The FE can derive a "pending" placeholder from
        // `survey != null && surveyResponse == null`.
        SurveySummary survey,
        // Personal-pillar ("general information") answers the member filled in
        // — surfaced on the results view so admins/members see who the subject
        // is at a glance. Empty list when the pipeline has no personal pillar
        // or the member hasn't answered any of its questions.
        List<PersonalInfoEntry> personalInfo,
        // True only when the viewer is entitled to the premium report but the
        // stored evaluation genuinely predates full premium detail (a legacy
        // row evaluated under the old free-tier contract, where corePattern was
        // never generated and strengths/developmentAreas were left empty). In
        // that case the blank premium sections are NOT a tier restriction and
        // NOT recoverable by an upgrade — the UI should surface a
        // "results predate full detail — re-evaluate to generate it" affordance
        // instead of rendering silently-empty premium blocks. Always false for
        // free-tier viewers (their blanks are an intentional tier restriction)
        // and false whenever stored premium detail is present.
        boolean premiumDetailUnavailable
) {
    /**
     * Backwards-compatible constructor for call sites that predate the
     * {@code premiumDetailUnavailable} flag (public-assessment and simulator
     * paths, which always carry full premium detail). Defaults the flag to
     * {@code false}.
     */
    public MemberResultsResponse(
            UUID submissionId,
            String pipelineName,
            BigDecimal overallScore,
            String summaryNarrative,
            List<String> strengths,
            List<String> developmentAreas,
            List<PillarScoreSummary> pillarScores,
            boolean premiumFeaturesAvailable,
            Instant evaluatedAt,
            String freeTierSummary,
            List<String> topStrengths,
            String maturityIndication,
            String premiumTeaser,
            String corePattern,
            String movingForwardNarrative,
            PostCompletionLinkDto postCompletion,
            SubmissionSurveyResponseDto surveyResponse,
            SurveySummary survey,
            List<PersonalInfoEntry> personalInfo) {
        this(submissionId, pipelineName, overallScore, summaryNarrative, strengths,
                developmentAreas, pillarScores, premiumFeaturesAvailable, evaluatedAt,
                freeTierSummary, topStrengths, maturityIndication, premiumTeaser,
                corePattern, movingForwardNarrative, postCompletion, surveyResponse,
                survey, personalInfo, false);
    }
}
