package com.bvisionry.pipeline.service;

import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.common.dto.PillarEvaluationResult;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.evaluation.EvaluationEngine;
import com.bvisionry.evaluation.EvaluationEngine.PillarResult;
import com.bvisionry.evaluation.EvaluationEngine.PipelineEvaluationResult;
import com.bvisionry.pipeline.dto.SimulateRequest;
import com.bvisionry.pipeline.dto.SimulatorPillarDetail;
import com.bvisionry.pipeline.dto.SimulatorProvenance;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.repository.PipelineRepository;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PillarScoreSummary;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class PipelineSimulationService {

    private final PipelineRepository pipelineRepository;
    private final EvaluationEngine evaluationEngine;
    private final AIConfigService aiConfigService;

    @Transactional(readOnly = true)
    public SimulationResult simulate(UUID pipelineId, SimulateRequest request) {
        Pipeline pipeline = pipelineRepository.findByIdWithPillarsAndQuestions(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", pipelineId.toString()));

        for (Pillar pillar : pipeline.getPillars()) {
            pillar.getQuestions().size();
        }

        List<Answer> answers = buildTransientAnswers(pipeline, request.answers());

        // Public (QR-link) assessments always evaluate at PREMIUM gating in the real
        // flow (EvaluationService: tier is PREMIUM when there is no assignment), so a
        // public simulation mirrors premium output but with the public system prompt
        // and the configured public-assessment model.
        boolean publicAssessment = request.publicAssessment();
        boolean isPremium = publicAssessment || request.tier() == SubscriptionTier.PREMIUM;

        // Generation is tier-agnostic now (always the full premium summary); the
        // free/premium distinction is applied below as a DISPLAY scope, mirroring the
        // real read path (MemberResultsService.applyViewerScope).
        String summaryPrompt = pipeline.getOverallSummaryPrompt();

        String modelOverride = publicAssessment
                ? aiConfigService.getConfigEntity().getPublicAssessmentModel()
                : null;

        PipelineEvaluationResult evalResult = evaluationEngine.evaluatePipeline(
                pipeline, null, answers, summaryPrompt, modelOverride, publicAssessment);

        List<PillarScoreSummary> pillarScores = new ArrayList<>();
        List<SimulatorPillarDetail> pillarDetails = new ArrayList<>();

        for (PillarResult pr : evalResult.pillarResults()) {
            pillarScores.add(new PillarScoreSummary(
                    pr.pillarId(), pr.pillarName(), pr.iconKey(),
                    pr.scorePercentage(), pr.maturityLabel()));

            if (isPremium) {
                pillarDetails.add(toSimulatorDetail(pr));
            }
        }

        var sr = evalResult.summary();
        // Mirror the real read-time scope (MemberResultsService.applyViewerScope): a
        // FREE simulation hides the premium-only blocks even though they were generated.
        MemberResultsResponse results = new MemberResultsResponse(
                null, pipeline.getName(), sr.overallScore(),
                sr.summaryNarrative(),
                isPremium ? sr.strengths() : List.of(),
                isPremium ? sr.developmentAreas() : List.of(),
                pillarScores, isPremium, Instant.now(),
                null, null, null, null,
                isPremium ? sr.corePattern() : null, sr.movingForwardNarrative(), null,
                null, null, List.of());

        SimulatorProvenance summaryProvenance = toSimulatorProvenance(sr.provenance());

        return new SimulationResult(
                results, pillarDetails, sr.rawResponse(),
                summaryProvenance, sr.summaryPromptSnapshot());
    }

    private static SimulatorPillarDetail toSimulatorDetail(PillarResult pr) {
        PillarEvaluationResult ai = pr.aiResult();
        String narrative = ai != null
                ? ai.whatThisScoreMeans()
                : (pr.rawResponse() != null ? pr.rawResponse() : "");
        return new SimulatorPillarDetail(
                pr.pillarId(), pr.pillarName(), pr.iconKey(),
                pr.scorePercentage(), pr.maturityLabel(),
                narrative,
                ai != null ? ai.whatsWorking() : List.of(),
                ai != null ? ai.whatCanImprove() : List.of(),
                ai != null ? ai.whyThisMattersForBusiness() : "",
                pr.selfAssessmentGap(),
                ai != null ? ai.evidence() : List.of(),
                pr.failed(),
                pr.failed() ? pr.rawResponse() : null,
                toSimulatorProvenance(pr.provenance()),
                pr.rubricSnapshot(),
                pr.rawResponse()
        );
    }

    private static SimulatorProvenance toSimulatorProvenance(OpenRouterChatService.Provenance p) {
        if (p == null) return null;
        return new SimulatorProvenance(p.model(), p.temperature(), p.systemPromptVersionId());
    }

    private List<Answer> buildTransientAnswers(Pipeline pipeline, Map<String, SimulateRequest.AnswerInput> simAnswers) {
        List<Answer> answers = new ArrayList<>();
        for (Pillar pillar : pipeline.getPillars()) {
            for (Question q : pillar.getQuestions()) {
                SimulateRequest.AnswerInput simAnswer = simAnswers.get(q.getId().toString());
                if (simAnswer == null) continue;
                Answer answer = new Answer();
                answer.setQuestion(q);
                answer.setResponseText(simAnswer.responseText());
                answer.setSelectedValue(simAnswer.selectedValue());
                answers.add(answer);
            }
        }
        return answers;
    }

    public record SimulationResult(
            MemberResultsResponse results,
            List<SimulatorPillarDetail> pillarDetails,
            String rawSummaryResponse,
            SimulatorProvenance summaryProvenance,
            String summaryPromptSnapshot
    ) {}
}
