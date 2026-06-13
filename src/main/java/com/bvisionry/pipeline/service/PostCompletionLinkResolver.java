package com.bvisionry.pipeline.service;

import com.bvisionry.pipeline.dto.PostCompletionLinkDto;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.repository.SurveyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Optional;
import java.util.UUID;

/**
 * Resolves the follow-up CTA paired to a pipeline. The two CTA shapes
 * (survey vs. external URL) are split into separate query methods so the
 * call sites that only care about one shape don't have to filter the union.
 * A combined {@link #resolveForCompletionEmail} is provided for the email
 * pipeline that legitimately needs to switch on either variant.
 */
@Component
@RequiredArgsConstructor
public class PostCompletionLinkResolver {

    static final String DEFAULT_SURVEY_LABEL = "Take our survey";
    static final String DEFAULT_EXTERNAL_LABEL = "Continue";

    private final SurveyRepository surveyRepository;

    /**
     * Resolve the survey CTA paired to this pipeline for a specific
     * submission. Returns empty when no survey is paired or the paired
     * survey is not currently {@code PUBLISHED}.
     *
     * @throws IllegalArgumentException if {@code submissionId} is null —
     *         survey CTAs are always submission-scoped, and silently
     *         returning empty for a null id was a bug magnet (the previous
     *         implementation did this and several callers relied on the
     *         silent-empty behavior to avoid passing the id at all).
     */
    public Optional<PostCompletionLinkDto.Survey> resolveSurveyForSubmission(
            Pipeline pipeline, UUID submissionId) {
        if (submissionId == null) {
            throw new IllegalArgumentException(
                    "submissionId is required to resolve a SURVEY post-completion link");
        }
        if (pipeline.getPostCompletionSurveyId() == null) {
            return Optional.empty();
        }
        Survey survey = surveyRepository.findById(pipeline.getPostCompletionSurveyId()).orElse(null);
        if (survey == null || survey.getStatus() != SurveyStatus.PUBLISHED) {
            return Optional.empty();
        }
        String label = pipeline.getPostCompletionLabel() != null
                ? pipeline.getPostCompletionLabel()
                : DEFAULT_SURVEY_LABEL;
        return Optional.of(new PostCompletionLinkDto.Survey(
                survey.getId(),
                survey.getName(),
                "/my/assessments/" + submissionId + "/post-completion-survey",
                label));
    }

    /**
     * Resolve the external-redirect CTA configured on this pipeline.
     * Returns empty if no external URL is set (or if it's blank).
     */
    public Optional<PostCompletionLinkDto.External> resolveExternal(Pipeline pipeline) {
        String url = pipeline.getPostCompletionExternalUrl();
        if (url == null || url.isBlank()) {
            return Optional.empty();
        }
        String label = pipeline.getPostCompletionLabel() != null
                ? pipeline.getPostCompletionLabel()
                : DEFAULT_EXTERNAL_LABEL;
        return Optional.of(new PostCompletionLinkDto.External(url, label));
    }

    /**
     * Resolve whichever post-completion CTA is configured on the pipeline,
     * preferring a paired survey. Used by the results-ready / survey-invite
     * email pipeline which needs to dispatch on either variant.
     */
    public Optional<PostCompletionLinkDto> resolveForCompletionEmail(
            Pipeline pipeline, UUID submissionId) {
        Optional<PostCompletionLinkDto.Survey> survey =
                resolveSurveyForSubmission(pipeline, submissionId);
        if (survey.isPresent()) {
            return survey.map(s -> s);
        }
        return resolveExternal(pipeline).map(e -> e);
    }
}
