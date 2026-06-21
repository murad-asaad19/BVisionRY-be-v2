package com.bvisionry.evaluation;

import com.bvisionry.aicalllog.dto.CallMetadata;
import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.aiconfig.service.OpenRouterChatService.Provenance;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.common.dto.OverallSummaryResult;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.pipeline.SystemQuestion;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.aiengine.confidence.ConfidenceGate;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.stream.Collectors;

/**
 * Pure evaluation logic — scoring + AI calls.
 */
@Component
@Slf4j
public class EvaluationEngine {

    private final ScoringService scoringService;
    private final OpenRouterChatService openRouterChatService;
    private final AIConfigService aiConfigService;
    /**
     * Per-pillar fan-out runs on a dedicated pool so it never competes with
     * top-level submission evaluations (which run on {@code evaluationExecutor}).
     * If both lived in the same pool, every parent thread blocked on
     * {@code join()} would be waiting for child tasks queued behind itself
     * once {@code maxPoolSize} submissions arrive concurrently.
     */
    private final Executor pillarExecutor;
    /** Bounded pool for borderline re-samples — never the pillar pool (fork-join starvation). */
    private final Executor escalationExecutor;
    private final ConfidenceGate confidenceGate;

    /** Confidence-gated self-consistency: borderline scores get extra samples. */
    private final boolean escalationEnabled;
    private final int borderlineMargin;
    private final int escalationSamples;
    private final String escalationModel;

    public EvaluationEngine(ScoringService scoringService,
                            OpenRouterChatService openRouterChatService,
                            AIConfigService aiConfigService,
                            ConfidenceGate confidenceGate,
                            @Qualifier("pillarExecutor") Executor pillarExecutor,
                            @Qualifier("escalationExecutor") Executor escalationExecutor,
                            @Value("${bvisionry.ai.escalation.enabled:true}") boolean escalationEnabled,
                            @Value("${bvisionry.ai.escalation.borderline-margin:3}") int borderlineMargin,
                            @Value("${bvisionry.ai.escalation.samples:2}") int escalationSamples,
                            @Value("${bvisionry.ai.escalation.model:}") String escalationModel) {
        this.scoringService = scoringService;
        this.openRouterChatService = openRouterChatService;
        this.aiConfigService = aiConfigService;
        this.confidenceGate = confidenceGate;
        this.pillarExecutor = pillarExecutor;
        this.escalationExecutor = escalationExecutor;
        this.escalationEnabled = escalationEnabled;
        this.borderlineMargin = borderlineMargin;
        this.escalationSamples = escalationSamples;
        this.escalationModel = escalationModel;
    }

    private static final int MAX_RAW_EXCERPT_CHARS = 500;
    private static final int MAX_EXCERPTS_PER_PILLAR = 3;
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    // ========== Result records ==========

    public record PillarResult(
            UUID pillarId, String pillarName, String iconKey,
            BigDecimal scorePercentage, String maturityLabel,
            PillarEvaluationResult aiResult, String rawResponse,
            Integer selfAssessmentGap,
            Provenance provenance,
            String rubricSnapshot,
            boolean failed
    ) {}

    public record SummaryResult(
            BigDecimal overallScore, String summaryNarrative,
            List<String> strengths, List<String> developmentAreas,
            String corePattern, String movingForwardNarrative, String rawResponse,
            Provenance provenance, String summaryPromptSnapshot,
            boolean failed
    ) {}

    public record PipelineEvaluationResult(
            List<PillarResult> pillarResults,
            SummaryResult summary
    ) {}

    // ========== Full pipeline evaluation ==========

    public PipelineEvaluationResult evaluatePipeline(Pipeline pipeline, List<Answer> answers,
                                                      String summaryPrompt) {
        return evaluatePipeline(pipeline, null, answers, summaryPrompt, null, false);
    }

    /**
     * @param modelOverride     Evaluation model for every AI call of this run (pillars +
     *                          overall summary); null = the AI-config default. Used to give
     *                          public (QR-link) assessments a dedicated model.
     * @param publicAssessment  True for public (QR-link) submissions, so every AI call uses
     *                          the dedicated PUBLIC_ASSESSMENT_SYSTEM_PROMPT instead of the
     *                          shared internal SYSTEM_PROMPT.
     */
    public PipelineEvaluationResult evaluatePipeline(Pipeline pipeline, UUID submissionId,
                                                      List<Answer> answers,
                                                      String summaryPrompt,
                                                      String modelOverride, boolean publicAssessment) {
        EvaluationContext ctx = prepareEvaluationContext(pipeline, answers);

        UUID pipelineId = pipeline.getId();
        List<PillarResult> pillarResults = evaluateAllPillars(
                ctx.evaluablePillars, ctx.answersByPillar, ctx.userContext, submissionId, pipelineId,
                modelOverride, publicAssessment);

        SummaryResult summary = generateOverallSummary(
                pillarResults, ctx.evaluableAnswers, summaryPrompt, ctx.userContext,
                submissionId, pipelineId, modelOverride, publicAssessment);

        return new PipelineEvaluationResult(pillarResults, summary);
    }

    /**
     * Slice answers + pillars into the shape both full and partial pipeline
     * runs need: standard pillars/answers (PERSONAL stripped), grouped answers
     * for parallel pillar fan-out, and the user-context block built from the
     * personal-pillar answers.
     */
    private EvaluationContext prepareEvaluationContext(Pipeline pipeline, List<Answer> answers) {
        List<Pillar> evaluablePillars = pipeline.getPillars().stream()
                .filter(p -> p.getType() != PillarType.PERSONAL)
                .toList();

        List<Answer> evaluableAnswers = answers.stream()
                .filter(a -> a.getQuestion().getPillar().getType() != PillarType.PERSONAL)
                .toList();

        List<Answer> personalAnswers = answers.stream()
                .filter(a -> a.getQuestion().getPillar().getType() == PillarType.PERSONAL)
                .toList();

        Map<UUID, List<Answer>> answersByPillar = evaluableAnswers.stream()
                .collect(Collectors.groupingBy(a -> a.getQuestion().getPillar().getId()));

        return new EvaluationContext(
                evaluablePillars, evaluableAnswers, answersByPillar, buildUserContext(personalAnswers));
    }

    private record EvaluationContext(
            List<Pillar> evaluablePillars,
            List<Answer> evaluableAnswers,
            Map<UUID, List<Answer>> answersByPillar,
            String userContext
    ) {}

    // ========== Partial pipeline re-evaluation ==========

    /**
     * Re-evaluate a subset of pillars on an already-evaluated submission and
     * regenerate the overall summary across the full pillar set. Used by the
     * "admin unlocks pillars for re-edit" flow — answers in the unlocked
     * pillars have been edited by the member, the existing pillar
     * evaluations for those pillars have been moved to history (and deleted)
     * by the caller, and we rebuild only the missing rows.
     *
     * <p>The summary regeneration sees a mix of fresh {@link PillarResult}s
     * (from this re-eval) and synthesized {@link PillarResult}s reconstructed
     * from {@code preservedEvaluations} so its narrative reflects the whole
     * pillar landscape, not just what changed.
     *
     * @param pipeline             Pipeline definition (drives which pillars are evaluable
     *                             vs personal, and the order of pillars in the summary).
     * @param submissionId         Threaded through to AI call logs.
     * @param answers              All answers for the submission (personal + standard).
     * @param pillarIdsToReeval    Standard pillars to re-evaluate. PERSONAL pillars are
     *                             ignored even if listed (they don't carry an evaluation).
     * @param preservedEvaluations Existing pillar_evaluations rows for pillars NOT being
     *                             re-evaluated. Used to synthesize PillarResults for the
     *                             summary call so the AI sees the full picture.
     * @param summaryPrompt        Same overall-summary prompt the full pipeline path uses.
     */
    public PipelineEvaluationResult evaluatePartialPipeline(Pipeline pipeline, UUID submissionId,
                                                             List<Answer> answers,
                                                             Set<UUID> pillarIdsToReeval,
                                                             List<PillarEvaluation> preservedEvaluations,
                                                             String summaryPrompt,
                                                             String modelOverride, boolean publicAssessment) {
        EvaluationContext ctx = prepareEvaluationContext(pipeline, answers);

        UUID pipelineId = pipeline.getId();

        List<Pillar> pillarsToReeval = ctx.evaluablePillars.stream()
                .filter(p -> pillarIdsToReeval.contains(p.getId()))
                .toList();

        List<PillarResult> reevaluatedResults = evaluateAllPillars(
                pillarsToReeval, ctx.answersByPillar, ctx.userContext, submissionId, pipelineId,
                modelOverride, publicAssessment);

        // Stitch reevaluated + preserved into a single ordered list so the
        // summary call sees the same shape as a full pipeline run. We key
        // off the pipeline's pillar order to keep the summary deterministic.
        Map<UUID, PillarResult> resultsById = new LinkedHashMap<>();
        Map<UUID, PillarResult> reevaluatedById = reevaluatedResults.stream()
                .collect(Collectors.toMap(PillarResult::pillarId, r -> r));
        Map<UUID, PillarEvaluation> preservedById = preservedEvaluations.stream()
                .collect(Collectors.toMap(pe -> pe.getPillar().getId(), pe -> pe));

        for (Pillar pillar : ctx.evaluablePillars) {
            PillarResult fresh = reevaluatedById.get(pillar.getId());
            if (fresh != null) {
                resultsById.put(pillar.getId(), fresh);
                continue;
            }
            PillarEvaluation preserved = preservedById.get(pillar.getId());
            if (preserved != null) {
                resultsById.put(pillar.getId(), toPillarResult(pillar, preserved));
            }
            // If neither exists (shouldn't happen — caller should preserve all
            // non-reevaluated pillars), the summary just sees one fewer pillar
            // rather than throwing here mid-evaluation.
        }

        List<PillarResult> allResults = new ArrayList<>(resultsById.values());

        SummaryResult summary = generateOverallSummary(
                allResults, ctx.evaluableAnswers, summaryPrompt, ctx.userContext,
                submissionId, pipelineId, modelOverride, publicAssessment);

        // The result's pillarResults list contains only the freshly re-evaluated
        // pillars — the caller writes those back to pillar_evaluations and
        // leaves preserved rows untouched.
        return new PipelineEvaluationResult(reevaluatedResults, summary);
    }

    /**
     * Reconstruct a {@link PillarResult} from a stored {@link PillarEvaluation}
     * so the summary generator can be fed a full pillar set during partial
     * re-evaluation. Best-effort — JSON columns may be null on legacy rows.
     */
    private static PillarResult toPillarResult(Pillar pillar, PillarEvaluation eval) {
        PillarEvaluationResult aiResult = new PillarEvaluationResult(
                eval.getScorePercentage() != null ? eval.getScorePercentage().intValue() : 0,
                eval.getAiScoreMeans(),
                eval.getAiWhatsWorking() != null ? eval.getAiWhatsWorking() : List.of(),
                eval.getAiWhatCanImprove() != null ? eval.getAiWhatCanImprove() : List.of(),
                eval.getAiBusinessRelevance(),
                eval.getAiEvidence() != null ? eval.getAiEvidence() : List.of()
        );
        Provenance provenance = eval.getAiModelUsed() != null
                ? new Provenance(eval.getAiModelUsed(), eval.getAiTemperature(), eval.getAiSystemPromptVersionId())
                : null;
        String rubric = eval.getAiRubricSnapshot() != null ? eval.getAiRubricSnapshot()
                : (pillar.getAiRubricInstructions() != null ? pillar.getAiRubricInstructions() : "");
        return new PillarResult(
                pillar.getId(), pillar.getName(), pillar.getIconKey(),
                eval.getScorePercentage() != null ? eval.getScorePercentage() : BigDecimal.ZERO,
                eval.getMaturityLabel(),
                aiResult, eval.getAiRawResponse(), eval.getSelfAssessmentGap(),
                provenance, rubric, false
        );
    }

    // ========== Pillar evaluation ==========

    public PillarResult evaluatePillar(Pillar pillar, List<Answer> answers) {
        return evaluatePillar(pillar, answers, null, null, null, null, false);
    }

    public PillarResult evaluatePillar(Pillar pillar, List<Answer> answers, String userContext) {
        return evaluatePillar(pillar, answers, userContext, null, null, null, false);
    }

    public PillarResult evaluatePillar(Pillar pillar, List<Answer> answers, String userContext,
                                        UUID submissionId, UUID pipelineId, String modelOverride,
                                        boolean publicAssessment) {
        String modelUsed = modelOverride != null
                ? modelOverride
                : aiConfigService.getConfigEntity().getDefaultEvaluationModel();

        String assessmentXml = buildAssessmentData(answers, pillar.getName());

        PillarEvaluationResult aiResult = null;
        String rawResponse = null;
        Provenance provenance = null;
        BigDecimal pillarScore = BigDecimal.ZERO;
        String rubric = pillar.getAiRubricInstructions() != null ? pillar.getAiRubricInstructions() : "";

        if (!assessmentXml.isBlank()) {
            CallMetadata metadata = CallMetadata.forPillar(submissionId, pipelineId, pillar.getName());
            var aiResponse = openRouterChatService.evaluatePillar(
                    rubric, assessmentXml, modelUsed, userContext, publicAssessment, metadata);
            aiResult = aiResponse.parsed();
            rawResponse = aiResponse.rawResponse();
            provenance = aiResponse.provenance();

            pillarScore = aiResult != null
                    ? BigDecimal.valueOf(aiResult.scorePercentage()) : BigDecimal.ZERO;

            // Confidence gating: a borderline score (near a maturity boundary) gets
            // extra self-consistency samples; take the median to stabilize it. Gated
            // so only borderline pillars pay the cost; model-agnostic (any model can
            // be re-sampled, no model-specific confidence field required).
            if (aiResult != null && escalationEnabled && escalationSamples > 0
                    && confidenceGate.isBorderline(pillarScore, pillar.getMaturityThresholds(), borderlineMargin)) {
                var chosen = escalateBorderline(aiResponse, rubric, assessmentXml, modelUsed,
                        userContext, publicAssessment, metadata, pillar.getName());
                aiResult = chosen.parsed();
                rawResponse = chosen.rawResponse();
                provenance = chosen.provenance();
                pillarScore = BigDecimal.valueOf(aiResult.scorePercentage());
            }
        }
        String maturityLabel = scoringService.deriveMaturityLabel(pillarScore, pillar.getMaturityThresholds());

        Integer selfAssessmentGap = null;
        var selfRatingOpt = answers.stream()
                .filter(a -> a.getQuestion().getType() == QuestionType.SELF_RATING)
                .findFirst();
        if (selfRatingOpt.isPresent()) {
            Answer selfRatingAnswer = selfRatingOpt.get();
            String ratingValue = selfRatingAnswer.getSelectedValue() != null
                    ? selfRatingAnswer.getSelectedValue()
                    : selfRatingAnswer.getResponseText();
            if (ratingValue != null && !ratingValue.isBlank()) {
                try {
                    int selfRating = Integer.parseInt(ratingValue.trim());
                    selfAssessmentGap = selfRating - pillarScore.intValue();
                } catch (NumberFormatException ignored) {}
            }
        }

        // We attempted an AI call (assessment had content) but got no parseable
        // result back even after the engine's repair retries — flag it so the
        // submission is marked NEEDS_REVIEW rather than persisting a fake zero.
        boolean failed = !assessmentXml.isBlank() && aiResult == null;

        return new PillarResult(
                pillar.getId(), pillar.getName(), pillar.getIconKey(),
                pillarScore, maturityLabel, aiResult, rawResponse, selfAssessmentGap,
                provenance, rubric, failed
        );
    }

    /**
     * Borderline confidence escalation: re-sample the pillar evaluation
     * {@code escalationSamples} more times (on {@code escalationModel} if configured,
     * else the same model), then return the sample whose score is closest to the
     * median across all attempts.
     *
     * <p>The extra samples run <b>in parallel</b> on a dedicated bounded
     * {@code escalationExecutor} (never the pillar pool — that would fork-join-starve
     * the very pillar tasks that spawned them) so the borderline pillar isn't blocked
     * serially. Each sample is tagged in the audit log via
     * {@link CallMetadata#forEscalationSample} so discarded samples are distinguishable
     * from the chosen one and token accounting stays honest. Failed/unparseable samples
     * are skipped; if no extra sample succeeds, the original response is returned.
     */
    private OpenRouterChatService.AIResponse<PillarEvaluationResult> escalateBorderline(
            OpenRouterChatService.AIResponse<PillarEvaluationResult> first,
            String rubric, String assessmentXml, String baseModel,
            String userContext, boolean publicAssessment, CallMetadata metadata, String pillarName) {

        String sampleModel = (escalationModel != null && !escalationModel.isBlank())
                ? escalationModel : baseModel;

        // Fan the extra samples out in parallel; each carries its own escalation-tagged
        // metadata so its ai_call_logs row is attributable and not double-counted.
        List<CompletableFuture<OpenRouterChatService.AIResponse<PillarEvaluationResult>>> futures =
                new ArrayList<>();
        for (int i = 0; i < escalationSamples; i++) {
            final int sampleIndex = i + 1;
            CallMetadata sampleMetadata = CallMetadata.forEscalationSample(metadata, sampleIndex);
            futures.add(CompletableFuture.supplyAsync(() -> {
                try {
                    return openRouterChatService.evaluatePillar(
                            rubric, assessmentXml, sampleModel, userContext, publicAssessment, sampleMetadata);
                } catch (Exception e) {
                    log.warn("Confidence escalation sample {} failed for pillar '{}': {}",
                            sampleIndex, pillarName, e.getMessage());
                    return null;
                }
            }, escalationExecutor));
        }

        List<OpenRouterChatService.AIResponse<PillarEvaluationResult>> samples = new ArrayList<>();
        samples.add(first);
        for (CompletableFuture<OpenRouterChatService.AIResponse<PillarEvaluationResult>> f : futures) {
            OpenRouterChatService.AIResponse<PillarEvaluationResult> sample = f.join();
            if (sample != null && sample.parsed() != null) {
                samples.add(sample);
            }
        }

        if (samples.size() == 1) {
            return first; // no usable extra samples — keep the original
        }
        List<Integer> scores = samples.stream().map(s -> s.parsed().scorePercentage()).toList();
        int median = confidenceGate.median(scores);
        OpenRouterChatService.AIResponse<PillarEvaluationResult> chosen = samples.stream()
                .min(java.util.Comparator.comparingInt(s -> Math.abs(s.parsed().scorePercentage() - median)))
                .orElse(first);
        log.info("Confidence escalation for pillar '{}': scores={} median={} (model={})",
                pillarName, scores, median, sampleModel);
        return chosen;
    }

    // ========== Parallel bulk evaluation ==========

    public List<PillarResult> evaluateAllPillars(List<Pillar> pillars, Map<UUID, List<Answer>> answersByPillar) {
        return evaluateAllPillars(pillars, answersByPillar, null, null, null, null, false);
    }

    public List<PillarResult> evaluateAllPillars(List<Pillar> pillars, Map<UUID, List<Answer>> answersByPillar,
                                                  String userContext) {
        return evaluateAllPillars(pillars, answersByPillar, userContext, null, null, null, false);
    }

    /**
     * Evaluate all pillars in parallel. Per-pillar failures are isolated — a single
     * pillar error no longer sinks the whole pipeline; a failed PillarResult is returned
     * for that pillar instead. {@code submissionId} and {@code pipelineId} are
     * threaded through for the AI call log so every row can be correlated back.
     */
    public List<PillarResult> evaluateAllPillars(List<Pillar> pillars, Map<UUID, List<Answer>> answersByPillar,
                                                  String userContext,
                                                  UUID submissionId, UUID pipelineId,
                                                  String modelOverride, boolean publicAssessment) {
        List<CompletableFuture<PillarResult>> futures = pillars.stream()
                .map(pillar -> CompletableFuture.supplyAsync(() -> {
                    try {
                        List<Answer> pillarAnswers = answersByPillar.getOrDefault(pillar.getId(), List.of());
                        return evaluatePillar(pillar, pillarAnswers, userContext, submissionId, pipelineId,
                                modelOverride, publicAssessment);
                    } catch (Exception e) {
                        log.error("Pillar evaluation failed for '{}' ({}): {}",
                                pillar.getName(), pillar.getId(), e.getMessage(), e);
                        String rubric = pillar.getAiRubricInstructions() != null
                                ? pillar.getAiRubricInstructions() : "";
                        return new PillarResult(
                                pillar.getId(), pillar.getName(), pillar.getIconKey(),
                                BigDecimal.ZERO, "Unknown", null,
                                "Pillar evaluation failed: " + e.getMessage(),
                                null, null, rubric, true);
                    }
                }, pillarExecutor))
                .toList();

        return futures.stream().map(CompletableFuture::join).toList();
    }

    // ========== Overall summary ==========

    public SummaryResult generateOverallSummary(List<PillarResult> pillarResults,
                                                  List<Answer> answers,
                                                  String overallSummaryPrompt,
                                                  String userContext) {
        return generateOverallSummary(pillarResults, answers, overallSummaryPrompt,
                userContext, null, null, null, false);
    }

    public SummaryResult generateOverallSummary(List<PillarResult> pillarResults,
                                                  List<Answer> answers,
                                                  String overallSummaryPrompt,
                                                  String userContext,
                                                  UUID submissionId, UUID pipelineId,
                                                  String modelOverride, boolean publicAssessment) {
        StringBuilder context = buildSummaryContext(pillarResults);

        // Only include FREE_TEXT and MULTI_INPUT excerpts — LIKERT/MULTIPLE_CHOICE signal
        // is already baked into the per-pillar AI summaries above.
        context.append("\n<raw_excerpts description=\"Capped quotes from text answers only, for cross-pillar pattern detection.\">\n");
        Map<UUID, List<Answer>> answersByPillar = answers.stream()
                .collect(Collectors.groupingBy(a -> a.getQuestion().getPillar().getId()));
        for (PillarResult pr : pillarResults) {
            int included = 0;
            for (Answer a : answersByPillar.getOrDefault(pr.pillarId(), List.of())) {
                if (included >= MAX_EXCERPTS_PER_PILLAR) break;
                QuestionType type = a.getQuestion() != null ? a.getQuestion().getType() : null;
                if (type != QuestionType.FREE_TEXT && type != QuestionType.MULTI_INPUT) continue;
                String text = a.getResponseText();
                if (text == null || text.isBlank()) continue;
                String trimmed = text.length() > MAX_RAW_EXCERPT_CHARS
                        ? text.substring(0, MAX_RAW_EXCERPT_CHARS) + "…"
                        : text;
                context.append("  <excerpt pillar=\"").append(escapeAttr(pr.pillarName())).append("\">")
                        .append(escapeText(trimmed))
                        .append("</excerpt>\n");
                included++;
            }
        }
        context.append("</raw_excerpts>\n");

        return callOverallSummary(context.toString(), overallSummaryPrompt, userContext,
                modelOverride, publicAssessment, CallMetadata.forSummary(submissionId, pipelineId));
    }

    // ========== Assessment XML builder ==========

    /**
     * Build the XML block sent as the user message to the AI. Each question becomes a
     * <response> element with stable attributes so the AI can cite answers by qid.
     */
    private String buildAssessmentData(List<Answer> answers, String pillarName) {
        if (answers == null || answers.isEmpty()) return "";

        StringBuilder sb = new StringBuilder();
        sb.append("<assessment_data pillar=\"").append(escapeAttr(pillarName)).append("\">\n");
        for (Answer answer : answers) {
            appendResponse(sb, answer, pillarName);
        }
        sb.append("</assessment_data>\n");
        return sb.toString();
    }

    private void appendResponse(StringBuilder sb, Answer answer, String pillarName) {
        Question question = answer.getQuestion();
        if (question == null) return;

        String qid = question.getId() != null ? question.getId().toString() : "unknown";
        String weight = question.getWeight() != null ? question.getWeight().toPlainString() : "1";
        String required = String.valueOf(question.isRequired());
        QuestionType type = question.getType();

        boolean unanswered = isUnanswered(answer, type);

        sb.append("  <response qid=\"").append(escapeAttr(qid))
                .append("\" type=\"").append(type)
                .append("\" weight=\"").append(escapeAttr(weight))
                .append("\" required=\"").append(required).append("\"");
        if (unanswered) {
            sb.append(" status=\"not_answered\"");
        }
        sb.append(">\n");

        sb.append("    <question>").append(escapeText(question.getPromptText())).append("</question>\n");

        if (unanswered) {
            sb.append("  </response>\n");
            return;
        }

        switch (type) {
            case LIKERT -> appendLikert(sb, answer, question);
            case MULTIPLE_CHOICE -> appendMultipleChoice(sb, answer, question);
            case FREE_TEXT -> appendFreeText(sb, answer);
            case SELF_RATING -> appendSelfRating(sb, answer, pillarName);
            case NUMBER -> sb.append("    <answer>").append(escapeText(answer.getSelectedValue()))
                    .append("</answer>\n");
            case MULTI_INPUT -> appendMultiInput(sb, answer, question);
            case DATE, PHONE, EMAIL -> {
                // Personal-pillar question types that can legally end up in a STANDARD pillar
                // if an admin configures one. Emit the best available value as plain text.
                String val = answer.getResponseText() != null && !answer.getResponseText().isBlank()
                        ? answer.getResponseText() : answer.getSelectedValue();
                sb.append("    <answer>").append(escapeText(val)).append("</answer>\n");
            }
        }

        sb.append("  </response>\n");
    }

    private void appendLikert(StringBuilder sb, Answer answer, Question question) {
        List<String> labels = readStringList(question.getConfigJson(), "labels");
        Integer selectedIdx = parseIntOrNull(answer.getSelectedValue());

        if (labels.isEmpty()) {
            sb.append("    <answer>").append(escapeText(answer.getSelectedValue()))
                    .append("</answer>\n");
            return;
        }
        sb.append("    <scale>\n");
        for (int i = 0; i < labels.size(); i++) {
            String value = String.valueOf(i + 1);
            boolean selected = selectedIdx != null && selectedIdx == i + 1;
            sb.append("      <option value=\"").append(value).append("\"");
            if (selected) sb.append(" selected=\"true\"");
            sb.append(">").append(escapeText(labels.get(i))).append("</option>\n");
        }
        sb.append("    </scale>\n");
    }

    private void appendMultipleChoice(StringBuilder sb, Answer answer, Question question) {
        Map<String, Object> cfg = question.getConfigJson();
        List<String> allOptions = readStringList(cfg, "options");
        String selectionMode = readString(cfg, "selectionMode");
        Integer maxSelections = readInteger(cfg, "maxSelections");

        Set<String> selected = Set.of();
        if (answer.getSelectedValue() != null) {
            selected = Arrays.stream(answer.getSelectedValue().split("\\|\\|\\|"))
                    .map(String::trim)
                    .collect(Collectors.toSet());
        }

        // Default selection mode: if config didn't specify, assume "multi" when >1 selected,
        // else "single". Admins can tighten by setting configJson.selectionMode.
        String effectiveMode = selectionMode != null ? selectionMode
                : (selected.size() > 1 ? "multi" : "single");

        sb.append("    <options selectionMode=\"").append(escapeAttr(effectiveMode)).append("\"");
        if (maxSelections != null) {
            sb.append(" maxSelections=\"").append(maxSelections).append("\"");
        }
        sb.append(">\n");

        if (allOptions.isEmpty()) {
            for (String val : selected) {
                sb.append("      <option selected=\"true\">").append(escapeText(val)).append("</option>\n");
            }
        } else {
            for (int i = 0; i < allOptions.size(); i++) {
                String option = allOptions.get(i);
                boolean isSelected = selected.contains(option);
                sb.append("      <option value=\"").append((char) ('a' + i)).append("\"");
                if (isSelected) sb.append(" selected=\"true\"");
                sb.append(">").append(escapeText(option)).append("</option>\n");
            }
        }
        sb.append("    </options>\n");

        int total = !allOptions.isEmpty() ? allOptions.size() : selected.size();
        sb.append("    <summary>Selected ").append(selected.size())
                .append(" of ").append(total).append(" options.</summary>\n");
    }

    private void appendFreeText(StringBuilder sb, Answer answer) {
        sb.append("    <answer>").append(escapeText(answer.getResponseText())).append("</answer>\n");
    }

    private void appendSelfRating(StringBuilder sb, Answer answer, String pillarName) {
        String value = answer.getSelectedValue() != null ? answer.getSelectedValue()
                : answer.getResponseText();
        sb.append("    <self_rating pillar=\"").append(escapeAttr(pillarName))
                .append("\" scale=\"0-100\">")
                .append(escapeText(value))
                .append("</self_rating>\n");
    }

    @SuppressWarnings("unchecked")
    private void appendMultiInput(StringBuilder sb, Answer answer, Question question) {
        Map<String, Object> cfg = question.getConfigJson();
        List<String> columns = readStringList(cfg, "columns");
        List<String> rows = readStringList(cfg, "rows");
        List<String> columnDescriptions = readStringList(cfg, "columnDescriptions");

        if (columns.isEmpty() || rows.isEmpty()) {
            sb.append("    <answer>").append(escapeText(answer.getResponseText())).append("</answer>\n");
            return;
        }

        if (!columnDescriptions.isEmpty()) {
            sb.append("    <column_descriptions>\n");
            for (int c = 0; c < columns.size(); c++) {
                String desc = c < columnDescriptions.size() ? columnDescriptions.get(c) : "";
                sb.append("      <col name=\"").append(escapeAttr(columns.get(c))).append("\">")
                        .append(escapeText(desc)).append("</col>\n");
            }
            sb.append("    </column_descriptions>\n");
        }

        try {
            Map<?, ?> parsed = OBJECT_MAPPER.readValue(answer.getResponseText(), Map.class);
            List<Map<String, String>> answerRows = (List<Map<String, String>>) parsed.get("rows");
            if (answerRows == null) {
                sb.append("    <answer>").append(escapeText(answer.getResponseText())).append("</answer>\n");
                return;
            }
            sb.append("    <rows>\n");
            for (int r = 0; r < rows.size(); r++) {
                sb.append("      <row label=\"").append(escapeAttr(rows.get(r))).append("\">\n");
                Map<String, String> cellValues = r < answerRows.size() ? answerRows.get(r) : Map.of();
                for (int c = 0; c < columns.size(); c++) {
                    String val = cellValues != null ? cellValues.getOrDefault(String.valueOf(c), "") : "";
                    sb.append("        <cell col=\"").append(escapeAttr(columns.get(c))).append("\">")
                            .append(val == null || val.isBlank() ? "" : escapeText(val))
                            .append("</cell>\n");
                }
                sb.append("      </row>\n");
            }
            sb.append("    </rows>\n");
        } catch (Exception e) {
            log.warn("Failed to parse MULTI_INPUT response for question '{}': {}",
                    question.getPromptText(), e.getMessage());
            sb.append("    <answer>").append(escapeText(answer.getResponseText())).append("</answer>\n");
        }
    }

    // ========== Summary context ==========

    private StringBuilder buildSummaryContext(List<PillarResult> pillarResults) {
        StringBuilder context = new StringBuilder();
        context.append("<pillar_results>\n");
        for (PillarResult pr : pillarResults) {
            context.append("  <pillar name=\"").append(escapeAttr(pr.pillarName()))
                    .append("\" score=\"").append(pr.scorePercentage())
                    .append("\" maturity=\"").append(escapeAttr(pr.maturityLabel())).append("\"");
            if (pr.selfAssessmentGap() != null) {
                context.append(" selfAssessmentGap=\"").append(pr.selfAssessmentGap()).append("\"");
            }
            if (pr.failed()) {
                context.append(" failed=\"true\"");
            }
            context.append(">\n");

            PillarEvaluationResult ai = pr.aiResult();
            if (ai != null) {
                if (ai.whatThisScoreMeans() != null && !ai.whatThisScoreMeans().isBlank()) {
                    context.append("    <meaning>").append(escapeText(ai.whatThisScoreMeans()))
                            .append("</meaning>\n");
                }
                if (ai.whatsWorking() != null && !ai.whatsWorking().isEmpty()) {
                    context.append("    <whats_working>\n");
                    for (String s : ai.whatsWorking()) {
                        context.append("      <item>").append(escapeText(s)).append("</item>\n");
                    }
                    context.append("    </whats_working>\n");
                }
                if (ai.whatCanImprove() != null && !ai.whatCanImprove().isEmpty()) {
                    context.append("    <what_can_improve>\n");
                    for (String s : ai.whatCanImprove()) {
                        context.append("      <item>").append(escapeText(s)).append("</item>\n");
                    }
                    context.append("    </what_can_improve>\n");
                }
                if (ai.whyThisMattersForBusiness() != null && !ai.whyThisMattersForBusiness().isBlank()) {
                    context.append("    <business_relevance>")
                            .append(escapeText(ai.whyThisMattersForBusiness()))
                            .append("</business_relevance>\n");
                }
            }
            context.append("  </pillar>\n");
        }
        context.append("</pillar_results>\n");
        return context;
    }

    private SummaryResult callOverallSummary(String context, String overallSummaryPrompt,
                                               String userContext,
                                               String modelOverride, boolean publicAssessment,
                                               CallMetadata metadata) {
        var aiResponse = openRouterChatService.generateOverallSummary(
                context, overallSummaryPrompt, userContext, modelOverride, publicAssessment, metadata);
        if (aiResponse.isParsed()) {
            OverallSummaryResult sr = aiResponse.parsed();
            return new SummaryResult(
                    BigDecimal.valueOf(sr.overallScorePercentage()), sr.summaryNarrative(),
                    sr.strengths(), sr.developmentAreas(),
                    sr.corePattern(), sr.movingForward(), aiResponse.rawResponse(),
                    aiResponse.provenance(),
                    overallSummaryPrompt, false);
        } else {
            // Narrative is intentionally blanked on parse failure so the results hero banner
            // never surfaces raw model output; the raw response is still retained below for
            // diagnostics. failed=true marks the submission NEEDS_REVIEW rather than EVALUATED.
            return new SummaryResult(BigDecimal.ZERO, "",
                    List.of(), List.of(), null, null, aiResponse.rawResponse(),
                    aiResponse.provenance(),
                    overallSummaryPrompt, true);
        }
    }

    // ========== User context ==========

    private String buildUserContext(List<Answer> personalAnswers) {
        String firstName = trimOrNull(findSystemAnswer(personalAnswers, SystemQuestion.FIRST_NAME));
        String lastName = trimOrNull(findSystemAnswer(personalAnswers, SystemQuestion.LAST_NAME));
        String gender = findSystemAnswer(personalAnswers, SystemQuestion.GENDER);

        StringBuilder sb = new StringBuilder();
        if (firstName != null || lastName != null) {
            String composed = (firstName != null ? firstName : "")
                    + (lastName != null ? (firstName != null ? " " : "") + lastName : "");
            sb.append("- Full name: ").append(escapeText(composed.trim())).append("\n");
            sb.append("- First name (use this when addressing them): ")
                    .append(escapeText(firstName != null ? firstName : composed.trim())).append("\n");
        } else {
            sb.append("- Full name: (not provided — address the person neutrally without inventing a name)\n");
        }

        if (gender != null && !gender.isBlank()) {
            sb.append("- Gender: ").append(escapeText(gender.trim())).append("\n");
        }

        if (personalAnswers != null && !personalAnswers.isEmpty()) {
            boolean appendedHeader = false;
            for (Answer a : personalAnswers) {
                Question q = a.getQuestion();
                if (q == null) continue;
                String key = q.getSystemKey();
                if (SystemQuestion.FIRST_NAME.equals(key)
                        || SystemQuestion.LAST_NAME.equals(key)
                        || SystemQuestion.GENDER.equals(key)) {
                    continue;
                }
                String value = pickAnswerValue(a);
                if (value == null || value.isBlank()) continue;
                if (!appendedHeader) {
                    sb.append("- Other general information:\n");
                    appendedHeader = true;
                }
                sb.append("    * ").append(escapeText(q.getPromptText())).append(": ").append(escapeText(value)).append("\n");
            }
        }
        return sb.toString();
    }

    // ========== Helpers ==========

    private static boolean isUnanswered(Answer a, QuestionType type) {
        if (a == null) return true;
        return switch (type) {
            case LIKERT, MULTIPLE_CHOICE, NUMBER, SELF_RATING ->
                    a.getSelectedValue() == null || a.getSelectedValue().isBlank();
            case FREE_TEXT, MULTI_INPUT, DATE, PHONE, EMAIL ->
                    a.getResponseText() == null || a.getResponseText().isBlank();
        };
    }

    private static List<String> readStringList(Map<String, Object> cfg, String key) {
        if (cfg == null) return List.of();
        Object val = cfg.get(key);
        if (val instanceof List<?> list) {
            return list.stream()
                    .map(v -> v == null ? "" : v.toString())
                    .toList();
        }
        return List.of();
    }

    private static String readString(Map<String, Object> cfg, String key) {
        if (cfg == null) return null;
        Object val = cfg.get(key);
        return val != null ? val.toString() : null;
    }

    private static Integer readInteger(Map<String, Object> cfg, String key) {
        if (cfg == null) return null;
        Object val = cfg.get(key);
        if (val instanceof Number n) return n.intValue();
        if (val instanceof String s) {
            try { return Integer.parseInt(s.trim()); } catch (NumberFormatException ignored) { return null; }
        }
        return null;
    }

    private static Integer parseIntOrNull(String s) {
        if (s == null) return null;
        try { return Integer.parseInt(s.trim()); } catch (NumberFormatException e) { return null; }
    }

    private static String trimOrNull(String s) {
        if (s == null) return null;
        String t = s.trim();
        return t.isEmpty() ? null : t;
    }

    private static String findSystemAnswer(List<Answer> personalAnswers, String systemKey) {
        if (personalAnswers == null) return null;
        for (Answer a : personalAnswers) {
            Question q = a.getQuestion();
            if (q == null) continue;
            if (systemKey.equals(q.getSystemKey())) {
                return pickAnswerValue(a);
            }
        }
        return null;
    }

    private static String pickAnswerValue(Answer a) {
        if (a.getResponseText() != null && !a.getResponseText().isBlank()) {
            return a.getResponseText().trim();
        }
        if (a.getSelectedValue() != null && !a.getSelectedValue().isBlank()) {
            return a.getSelectedValue().replace("|||", ", ").trim();
        }
        return null;
    }

    private static String escapeText(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }

    private static String escapeAttr(String s) {
        if (s == null) return "";
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '&' -> out.append("&amp;");
                case '<' -> out.append("&lt;");
                case '>' -> out.append("&gt;");
                case '"' -> out.append("&quot;");
                case '\'' -> out.append("&apos;");
                default -> out.append(c);
            }
        }
        return out.toString();
    }
}
