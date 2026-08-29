package com.bvisionry.survey.service;

import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.entity.SurveyVisibility;
import com.bvisionry.survey.repository.SurveyRepository;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The rule for "may an anonymous visitor be sent to this survey" lives here,
 * so every feature that pairs a survey to a completion screen inherits ONE
 * answer. Each state that must suppress the CTA is pinned separately: a
 * collapsed test would still pass if one of the three filters were dropped,
 * and the failure mode is a printed QR leading a stranger to a 404.
 */
@ExtendWith(MockitoExtension.class)
class PublicSurveyLinkServiceTest {

    @Mock private SurveyRepository surveyRepository;

    @InjectMocks private PublicSurveyLinkService service;

    private Survey survey(SurveyStatus status, SurveyVisibility visibility, UUID publicToken) {
        Survey survey = new Survey();
        survey.setId(UUID.randomUUID());
        survey.setName("Founder Mindset Pulse");
        survey.setStatus(status);
        survey.setVisibility(visibility);
        survey.setPublicToken(publicToken);
        when(surveyRepository.findById(survey.getId())).thenReturn(Optional.of(survey));
        return survey;
    }

    @Test
    void publishedPublicSurvey_resolvesToItsPublicLink() {
        UUID publicToken = UUID.randomUUID();
        Survey survey = survey(SurveyStatus.PUBLISHED, SurveyVisibility.PUBLIC, publicToken);

        var link = service.publicLink(survey.getId());

        assertThat(link).isPresent();
        assertThat(link.get().token()).isEqualTo(publicToken);
        assertThat(link.get().name()).isEqualTo("Founder Mindset Pulse");
    }

    @Test
    void privateSurvey_isNotOfferable() {
        Survey survey = survey(SurveyStatus.PUBLISHED, SurveyVisibility.PRIVATE, UUID.randomUUID());

        assertThat(service.publicLink(survey.getId())).isEmpty();
    }

    @Test
    void unpublishedSurvey_isNotOfferable() {
        Survey survey = survey(SurveyStatus.DRAFT, SurveyVisibility.PUBLIC, UUID.randomUUID());

        assertThat(service.publicLink(survey.getId())).isEmpty();
    }

    @Test
    void surveyWithNoMintedToken_isNotOfferable() {
        Survey survey = survey(SurveyStatus.PUBLISHED, SurveyVisibility.PUBLIC, null);

        assertThat(service.publicLink(survey.getId())).isEmpty();
    }

    @Test
    void deletedSurvey_isNotOfferable() {
        UUID gone = UUID.randomUUID();
        when(surveyRepository.findById(gone)).thenReturn(Optional.empty());

        assertThat(service.publicLink(gone)).isEmpty();
    }

    /** An unpaired caller passes null; that must not become a repository hit. */
    @Test
    void nullId_isNotOfferableAndNeverQueries() {
        assertThat(service.publicLink(null)).isEmpty();
        assertThat(service.exists(null)).isFalse();

        verify(surveyRepository, never()).findById(null);
        verify(surveyRepository, never()).existsById(null);
    }

    @Test
    void exists_ignoresStatusAndVisibility() {
        // Pairing a DRAFT survey is legitimate — it may be published later — so
        // existence must NOT reuse the offerable predicate.
        UUID draftId = UUID.randomUUID();
        when(surveyRepository.existsById(draftId)).thenReturn(true);

        assertThat(service.exists(draftId)).isTrue();
    }
}
