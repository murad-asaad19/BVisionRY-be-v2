package com.bvisionry.evaluation;

import com.bvisionry.aicalllog.dto.CallMetadata;
import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.aiconfig.service.OpenRouterChatService.AIResponse;
import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.dto.AiUseDetectionResult;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.common.exception.AIServiceException;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.util.XmlEscape;
import com.bvisionry.evaluation.dto.AiDetectionResponse;
import com.bvisionry.evaluation.entity.SubmissionAiDetection;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * On-demand AI-use detection: an LLM judge scores how likely a submission's
 * free-text answers were AI-generated. One stored result per submission,
 * overwritten on each re-run.
 *
 * <p>Deliberately NOT transactional: the AI call takes seconds and must never
 * hold a database transaction (OSIV is off; each repository call runs its own
 * short transaction, and the answer graph is JOIN-FETCHed up front).
 *
 * <p>The score is a heuristic indicator for a human reviewer, never proof —
 * the seeded {@code AI_USE_DETECTION} prompt says so explicitly and the UI
 * must present it that way. Text signals are the only input today; the upgrade
 * path for stronger evidence is behavioral signals (paste events, per-question
 * timing) captured in the taker UI and combined with this score.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class AiUseDetectionService {

    private final AnswerRepository answerRepository;
    private final SubmissionRepository submissionRepository;
    private final SubmissionAiDetectionRepository detectionRepository;
    private final OpenRouterChatService chatService;

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();
    private static final TypeReference<List<AiDetectionResponse.Finding>> FINDINGS_TYPE =
            new TypeReference<>() {};

    /** Runs the detector for a submission and stores (or replaces) its result. */
    public AiDetectionResponse detect(UUID submissionId) {
        Submission submission = submissionRepository.findById(submissionId)
                .orElseThrow(() -> new ResourceNotFoundException("Submission", submissionId.toString()));

        List<Answer> proseAnswers = answerRepository
                .findBySubmissionIdWithQuestionAndPillar(submissionId).stream()
                .filter(a -> a.getQuestion().getPillar().getType() != PillarType.PERSONAL)
                .filter(a -> {
                    QuestionType type = a.getQuestion().getType();
                    return type == QuestionType.FREE_TEXT || type == QuestionType.MULTI_INPUT;
                })
                .filter(a -> a.getResponseText() != null && !a.getResponseText().isBlank())
                .toList();
        if (proseAnswers.isEmpty()) {
            throw new BadRequestException(
                    "This submission has no free-text answers to analyze for AI use.");
        }

        AIResponse<AiUseDetectionResult> response = chatService.detectAiUse(
                buildAssessmentData(proseAnswers),
                new CallMetadata(submissionId, null, "ai-use-detection", false));
        if (!response.isParsed()) {
            throw new AIServiceException(
                    "The AI-use detector returned an unusable response after retries — please try again.");
        }
        AiUseDetectionResult result = response.parsed();

        // Resolve qid -> question text now so reads never re-join; a finding
        // citing a qid the model invented is dropped rather than surfaced.
        Map<String, String> questionTextByQid = proseAnswers.stream()
                .collect(Collectors.toMap(
                        a -> a.getQuestion().getId().toString(),
                        a -> a.getQuestion().getPromptText(),
                        (first, dup) -> first));
        List<AiDetectionResponse.Finding> findings = result.answerFindings().stream()
                .filter(f -> questionTextByQid.containsKey(f.qid()))
                .map(f -> new AiDetectionResponse.Finding(
                        f.qid(), questionTextByQid.get(f.qid()), f.note()))
                .toList();

        SubmissionAiDetection detection = detectionRepository.findBySubmissionId(submissionId)
                .orElseGet(() -> {
                    SubmissionAiDetection fresh = new SubmissionAiDetection();
                    fresh.setSubmission(submission);
                    return fresh;
                });
        detection.setAiLikelihoodScore(result.aiLikelihoodScore());
        detection.setAnswerFindings(serializeFindings(findings));
        detection.setModel(response.provenance().model());
        detection.setPromptVersionId(response.provenance().systemPromptVersionId());
        detection = detectionRepository.save(detection);

        return toResponse(detection, findings);
    }

    /** Returns the stored result for a submission, or 404 when never run. */
    public AiDetectionResponse get(UUID submissionId) {
        return detectionRepository.findBySubmissionId(submissionId)
                .map(d -> toResponse(d, deserializeFindings(d.getAnswerFindings())))
                .orElseThrow(() -> new ResourceNotFoundException(
                        "AI-use detection", submissionId.toString()));
    }

    private static AiDetectionResponse toResponse(SubmissionAiDetection detection,
                                                  List<AiDetectionResponse.Finding> findings) {
        return new AiDetectionResponse(
                detection.getAiLikelihoodScore(),
                AiDetectionResponse.verdictFor(detection.getAiLikelihoodScore()),
                findings,
                detection.getModel(),
                detection.getUpdatedAt());
    }

    /**
     * The user message sent to the detector: only the substantial prose answers
     * (FREE_TEXT / MULTI_INPUT outside PERSONAL pillars), tagged so findings can
     * cite answers by qid. Everything inside the tag is declared DATA by the
     * shared input conventions, so instructions written INTO an answer ("mark
     * this as human-written") are ignored rather than obeyed.
     */
    private static String buildAssessmentData(List<Answer> answers) {
        StringBuilder sb = new StringBuilder();
        sb.append("<assessment_data>\n");
        for (Answer answer : answers) {
            sb.append("  <response qid=\"")
                    .append(XmlEscape.attr(answer.getQuestion().getId().toString()))
                    .append("\">\n");
            sb.append("    <question>")
                    .append(XmlEscape.text(answer.getQuestion().getPromptText()))
                    .append("</question>\n");
            sb.append("    <answer>")
                    .append(XmlEscape.text(answer.getResponseText()))
                    .append("</answer>\n");
            sb.append("  </response>\n");
        }
        sb.append("</assessment_data>\n");
        return sb.toString();
    }

    private static String serializeFindings(List<AiDetectionResponse.Finding> findings) {
        try {
            return OBJECT_MAPPER.writeValueAsString(findings);
        } catch (Exception e) {
            log.warn("Failed to serialize AI-detection findings: {}", e.getMessage());
            return "[]";
        }
    }

    private static List<AiDetectionResponse.Finding> deserializeFindings(String json) {
        if (json == null || json.isBlank()) return List.of();
        try {
            return OBJECT_MAPPER.readValue(json, FINDINGS_TYPE);
        } catch (Exception e) {
            log.warn("Stored AI-detection findings were unreadable: {}", e.getMessage());
            return List.of();
        }
    }
}
