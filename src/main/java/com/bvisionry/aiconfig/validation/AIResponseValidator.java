package com.bvisionry.aiconfig.validation;

import com.bvisionry.common.dto.OverallSummaryResult;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.dto.TeamInsightResult;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.regex.Pattern;

@Component
public class AIResponseValidator {

    // Inline qid references like "(qid: <uuid>)", "qid: <uuid>", "(Q: <hex>)",
    // or "Q: <hex>" leak from the AI into prose fields. Strip them before
    // persistence so every downstream consumer (in-app, TryItOut, PDF, Excel)
    // sees clean output. Legitimate qids live in the structured evidence field.
    private static final Pattern QID_PATTERN =
            Pattern.compile("\\s*\\(?\\s*(?:qid|Q):\\s*[0-9a-fA-F-]{8,}\\s*\\)?",
                    Pattern.CASE_INSENSITIVE);

    /**
     * Validates and sanitizes a PillarEvaluationResult.
     * Throws IllegalArgumentException for invalid scores. Output length is
     * controlled via the prompt — no length caps are enforced here.
     */
    public PillarEvaluationResult validatePillarResult(PillarEvaluationResult result) {
        validateScore(result.scorePercentage());

        return new PillarEvaluationResult(
                result.scorePercentage(),
                cleanProse(result.whatThisScoreMeans()),
                cleanProseList(result.whatsWorking()),
                cleanProseList(result.whatCanImprove()),
                cleanProse(result.whyThisMattersForBusiness()),
                result.evidence()
        );
    }

    /**
     * Validates and sanitizes an OverallSummaryResult.
     */
    public OverallSummaryResult validateOverallSummaryResult(OverallSummaryResult result) {
        validateScore(result.overallScorePercentage());

        return new OverallSummaryResult(
                result.overallScorePercentage(),
                cleanProse(result.summaryNarrative()),
                cleanProseList(result.strengths()),
                cleanProseList(result.developmentAreas()),
                cleanProse(result.corePattern()),
                cleanProse(result.movingForward())
        );
    }

    /**
     * Validates a TeamInsightResult. No score field — validates nested structure.
     */
    public TeamInsightResult validateTeamInsightResult(TeamInsightResult result) {
        if (result.teamThemes() == null) {
            throw new IllegalArgumentException("Team themes are required");
        }

        TeamInsightResult.TeamThemes themes = new TeamInsightResult.TeamThemes(
                cleanProseList(result.teamThemes().commonStrengths()),
                cleanProseList(result.teamThemes().growthEdges()),
                cleanProseList(result.teamThemes().patterns()),
                cleanProseList(result.teamThemes().recommendations())
        );

        List<TeamInsightResult.IndividualCoaching> coaching = result.individualCoaching() != null
                ? result.individualCoaching().stream()
                    .map(c -> new TeamInsightResult.IndividualCoaching(
                            c.memberId(),
                            cleanProseList(c.focusAreas()),
                            cleanProseList(c.suggestedActions())))
                    .toList()
                : List.of();

        TeamInsightResult.Benchmarking benchmarking = result.benchmarking() != null
                ? new TeamInsightResult.Benchmarking(
                    cleanProse(result.benchmarking().teamVsPlatformComparison()),
                    cleanProseList(result.benchmarking().outlierPillars()))
                : new TeamInsightResult.Benchmarking("", List.of());

        return new TeamInsightResult(themes, coaching, benchmarking);
    }

    private void validateScore(int score) {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("Score must be between 0 and 100, got: " + score);
        }
    }

    private String cleanProse(String text) {
        if (text == null) return "";
        return QID_PATTERN.matcher(text).replaceAll("").replaceAll("\\s+", " ").trim();
    }

    private List<String> cleanProseList(List<String> items) {
        if (items == null) return List.of();
        return items.stream()
                .map(this::cleanProse)
                .toList();
    }
}
