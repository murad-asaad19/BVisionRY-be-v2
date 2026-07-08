package com.bvisionry.evaluation;

import com.bvisionry.aicalllog.dto.CallMetadata;
import com.bvisionry.aiconfig.service.OpenRouterChatService;
import com.bvisionry.aiconfig.service.OpenRouterChatService.AIResponse;
import com.bvisionry.aiconfig.service.OpenRouterChatService.Provenance;
import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.dto.AiUseDetectionResult;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.evaluation.dto.AiDetectionResponse;
import com.bvisionry.evaluation.entity.SubmissionAiDetection;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Question;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AiUseDetectionServiceTest {

    @Mock
    private AnswerRepository answerRepository;
    @Mock
    private SubmissionRepository submissionRepository;
    @Mock
    private SubmissionAiDetectionRepository detectionRepository;
    @Mock
    private OpenRouterChatService chatService;

    @InjectMocks
    private AiUseDetectionService service;

    private static final UUID SUBMISSION_ID = UUID.randomUUID();

    private static Answer answer(UUID questionId, QuestionType type, PillarType pillarType, String text) {
        Pillar pillar = new Pillar();
        pillar.setType(pillarType);
        Question question = new Question();
        question.setId(questionId);
        question.setType(type);
        question.setPromptText("Q-" + questionId);
        question.setPillar(pillar);
        Answer answer = new Answer();
        answer.setQuestion(question);
        answer.setResponseText(text);
        return answer;
    }

    @Test
    void verdictBandsFollowTheDocumentedCutoffs() {
        assertThat(AiDetectionResponse.verdictFor(0)).isEqualTo("LIKELY_HUMAN");
        assertThat(AiDetectionResponse.verdictFor(24)).isEqualTo("LIKELY_HUMAN");
        assertThat(AiDetectionResponse.verdictFor(25)).isEqualTo("UNCERTAIN");
        assertThat(AiDetectionResponse.verdictFor(49)).isEqualTo("UNCERTAIN");
        assertThat(AiDetectionResponse.verdictFor(50)).isEqualTo("POSSIBLY_AI");
        assertThat(AiDetectionResponse.verdictFor(74)).isEqualTo("POSSIBLY_AI");
        assertThat(AiDetectionResponse.verdictFor(75)).isEqualTo("LIKELY_AI");
        assertThat(AiDetectionResponse.verdictFor(100)).isEqualTo("LIKELY_AI");
    }

    @Test
    void detectSendsOnlyProseAnswersAndDropsHallucinatedQids() {
        UUID freeTextQid = UUID.randomUUID();
        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(new Submission()));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(SUBMISSION_ID)).thenReturn(List.of(
                answer(freeTextQid, QuestionType.FREE_TEXT, PillarType.STANDARD,
                        "We tried <cold> outreach & it failed."),
                // Excluded: PERSONAL pillar, non-prose type, blank text.
                answer(UUID.randomUUID(), QuestionType.FREE_TEXT, PillarType.PERSONAL, "My name"),
                answer(UUID.randomUUID(), QuestionType.LIKERT, PillarType.STANDARD, "3"),
                answer(UUID.randomUUID(), QuestionType.FREE_TEXT, PillarType.STANDARD, "  ")));

        AiUseDetectionResult aiResult = new AiUseDetectionResult(80, List.of(
                new AiUseDetectionResult.AnswerFinding(freeTextQid.toString(), "Template-like structure"),
                new AiUseDetectionResult.AnswerFinding(UUID.randomUUID().toString(), "hallucinated qid")));
        ArgumentCaptor<String> xmlCaptor = ArgumentCaptor.forClass(String.class);
        when(chatService.detectAiUse(xmlCaptor.capture(), any(CallMetadata.class)))
                .thenReturn(new AIResponse<>(aiResult, "{}",
                        new Provenance("test-model", BigDecimal.valueOf(0.3), UUID.randomUUID())));
        when(detectionRepository.findBySubmissionId(SUBMISSION_ID)).thenReturn(Optional.empty());
        when(detectionRepository.save(any(SubmissionAiDetection.class)))
                .thenAnswer(inv -> inv.getArgument(0));

        AiDetectionResponse response = service.detect(SUBMISSION_ID);

        // Only the qualifying prose answer is in the XML, with its markup escaped.
        String xml = xmlCaptor.getValue();
        assertThat(xml).contains(freeTextQid.toString())
                .contains("We tried &lt;cold&gt; outreach &amp; it failed.")
                .doesNotContain("My name")
                .doesNotContain("<answer>3</answer>");

        assertThat(response.aiLikelihoodScore()).isEqualTo(80);
        assertThat(response.verdict()).isEqualTo("LIKELY_AI");
        // The finding citing an invented qid is dropped; the real one keeps its question text.
        assertThat(response.findings()).hasSize(1);
        assertThat(response.findings().get(0).questionText()).isEqualTo("Q-" + freeTextQid);
    }

    @Test
    void detectRejectsSubmissionsWithoutProseAnswers() {
        when(submissionRepository.findById(SUBMISSION_ID)).thenReturn(Optional.of(new Submission()));
        when(answerRepository.findBySubmissionIdWithQuestionAndPillar(SUBMISSION_ID)).thenReturn(List.of(
                answer(UUID.randomUUID(), QuestionType.LIKERT, PillarType.STANDARD, "2")));

        assertThatThrownBy(() -> service.detect(SUBMISSION_ID))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("no free-text answers");
    }
}
