package com.bvisionry.pipeline.service;

import com.bvisionry.pipeline.dto.PostCompletionLinkDto;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.repository.SurveyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

/**
 * Pins the survey-pairing rules that decide whether a member gets a
 * post-completion survey invite.
 *
 * <p>The status gate here is load-bearing and easy to misread as an edge case:
 * an admin pairs a PUBLISHED survey, later unpublishes it to edit it (a
 * PUBLISHED survey is immutable — see {@code SurveyService#requireEditable}),
 * and never republishes. From then on every member who finishes the assessment
 * silently gets no invite email and no CTA on their results, while the pipeline
 * editor still shows the pairing. These tests exist so that behavior can only
 * change deliberately.
 */
@ExtendWith(MockitoExtension.class)
class PostCompletionLinkResolverTest {

    @Mock private SurveyRepository surveyRepository;

    @InjectMocks private PostCompletionLinkResolver resolver;

    private Pipeline pipeline;
    private UUID submissionId;
    private UUID surveyId;

    @BeforeEach
    void setUp() {
        submissionId = UUID.randomUUID();
        surveyId = UUID.randomUUID();
        pipeline = new Pipeline();
        pipeline.setId(UUID.randomUUID());
        pipeline.setName("Mindset Distance Assessment");
        pipeline.setPostCompletionSurveyId(surveyId);
    }

    private Survey surveyWithStatus(SurveyStatus status) {
        Survey survey = new Survey();
        survey.setId(surveyId);
        survey.setName("Post-Assessment Feedback");
        survey.setStatus(status);
        return survey;
    }

    @Test
    void publishedSurvey_resolvesToSubmissionScopedCta() {
        when(surveyRepository.findById(surveyId))
                .thenReturn(Optional.of(surveyWithStatus(SurveyStatus.PUBLISHED)));

        Optional<PostCompletionLinkDto.Survey> result =
                resolver.resolveSurveyForSubmission(pipeline, submissionId);

        assertThat(result).isPresent();
        assertThat(result.get().surveyId()).isEqualTo(surveyId);
        assertThat(result.get().surveyName()).isEqualTo("Post-Assessment Feedback");
        assertThat(result.get().url())
                .isEqualTo("/app/assessments/" + submissionId + "/post-completion-survey");
    }

    /**
     * The reported failure: the paired survey was unpublished so it could be
     * edited, so no survey-invite email is sent. Empty here is correct — a
     * DRAFT survey rejects responses, so inviting members to it would be worse
     * than sending nothing — but it must stay a deliberate, tested decision.
     */
    @Test
    void draftSurvey_resolvesEmpty_soNoInviteIsSent() {
        when(surveyRepository.findById(surveyId))
                .thenReturn(Optional.of(surveyWithStatus(SurveyStatus.DRAFT)));

        assertThat(resolver.resolveSurveyForSubmission(pipeline, submissionId)).isEmpty();
    }

    @Test
    void closedSurvey_resolvesEmpty_soNoInviteIsSent() {
        when(surveyRepository.findById(surveyId))
                .thenReturn(Optional.of(surveyWithStatus(SurveyStatus.CLOSED)));

        assertThat(resolver.resolveSurveyForSubmission(pipeline, submissionId)).isEmpty();
    }

    @Test
    void deletedSurvey_resolvesEmpty() {
        when(surveyRepository.findById(surveyId)).thenReturn(Optional.empty());

        assertThat(resolver.resolveSurveyForSubmission(pipeline, submissionId)).isEmpty();
    }

    @Test
    void noPairing_resolvesEmptyWithoutHittingTheRepository() {
        pipeline.setPostCompletionSurveyId(null);
        lenient().when(surveyRepository.findById(any())).thenThrow(
                new AssertionError("must not query surveys when nothing is paired"));

        assertThat(resolver.resolveSurveyForSubmission(pipeline, submissionId)).isEmpty();
    }

    /**
     * The combined email-path entry point must fall through to the EXTERNAL CTA
     * when the paired survey is unusable — otherwise unpublishing a survey would
     * also silently drop a configured external redirect.
     */
    @Test
    void unusableSurvey_fallsBackToExternalCta() {
        pipeline.setPostCompletionExternalUrl("https://example.com/next-steps");
        pipeline.setPostCompletionLabel("Continue");
        when(surveyRepository.findById(surveyId))
                .thenReturn(Optional.of(surveyWithStatus(SurveyStatus.DRAFT)));

        Optional<PostCompletionLinkDto> result =
                resolver.resolveForCompletionEmail(pipeline, submissionId);

        assertThat(result).containsInstanceOf(PostCompletionLinkDto.External.class);
    }
}
