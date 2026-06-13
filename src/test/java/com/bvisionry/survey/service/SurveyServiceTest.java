package com.bvisionry.survey.service;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.survey.dto.SurveyCreateRequest;
import com.bvisionry.survey.dto.SurveyDto;
import com.bvisionry.survey.dto.SurveyStatusRequest;
import com.bvisionry.survey.entity.RespondentFieldMode;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyPillar;
import com.bvisionry.survey.entity.SurveyQuestion;
import com.bvisionry.survey.entity.SurveyQuestionType;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.entity.SurveyVisibility;
import com.bvisionry.survey.repository.SurveyRepository;
import com.bvisionry.survey.repository.SurveyResponseRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class SurveyServiceTest {

    @Mock SurveyRepository surveyRepository;
    @Mock SurveyResponseRepository responseRepository;

    SurveyMapper mapper;
    SurveyService service;

    private UUID createdBy;

    @BeforeEach
    void setUp() {
        mapper = new SurveyMapper();
        service = new SurveyService(surveyRepository, responseRepository, mapper);
        createdBy = UUID.randomUUID();
    }

    @Test
    void create_savesDraftSurveyWithDefaults() {
        when(surveyRepository.save(any(Survey.class)))
                .thenAnswer(inv -> {
                    Survey s = inv.getArgument(0);
                    s.setId(UUID.randomUUID());
                    return s;
                });

        SurveyDto dto = service.create(
                new SurveyCreateRequest("Feedback", "desc", null, null, null), createdBy);

        assertThat(dto.status()).isEqualTo(SurveyStatus.DRAFT);
        assertThat(dto.visibility()).isEqualTo(SurveyVisibility.PRIVATE);
        assertThat(dto.respondentEmailMode()).isEqualTo(RespondentFieldMode.NONE);
        assertThat(dto.respondentNameMode()).isEqualTo(RespondentFieldMode.NONE);
        assertThat(dto.publicToken()).isNull();
    }

    @Test
    void publish_publicSurvey_mintsTokenAndTimestamp() {
        Survey survey = buildValidDraftSurvey();
        survey.setVisibility(SurveyVisibility.PUBLIC);
        when(surveyRepository.findById(survey.getId())).thenReturn(Optional.of(survey));
        when(surveyRepository.save(any(Survey.class))).thenAnswer(inv -> inv.getArgument(0));

        SurveyDto dto = service.transitionStatus(
                survey.getId(), new SurveyStatusRequest(SurveyStatus.PUBLISHED));

        assertThat(dto.status()).isEqualTo(SurveyStatus.PUBLISHED);
        assertThat(dto.publicToken()).isNotNull();
        assertThat(dto.publishedAt()).isNotNull();
    }

    @Test
    void publish_privateSurvey_doesNotMintToken() {
        Survey survey = buildValidDraftSurvey();
        survey.setVisibility(SurveyVisibility.PRIVATE);
        when(surveyRepository.findById(survey.getId())).thenReturn(Optional.of(survey));
        when(surveyRepository.save(any(Survey.class))).thenAnswer(inv -> inv.getArgument(0));

        SurveyDto dto = service.transitionStatus(
                survey.getId(), new SurveyStatusRequest(SurveyStatus.PUBLISHED));

        assertThat(dto.status()).isEqualTo(SurveyStatus.PUBLISHED);
        assertThat(dto.publicToken()).isNull();
        assertThat(dto.publishedAt()).isNotNull();
    }

    @Test
    void flipVisibilityToPrivate_clearsPublicToken() {
        Survey survey = buildPublishedSurvey();
        survey.setVisibility(SurveyVisibility.PUBLIC);
        UUID id = survey.getId();
        when(surveyRepository.findById(id)).thenReturn(Optional.of(survey));
        when(surveyRepository.save(any(Survey.class))).thenAnswer(inv -> inv.getArgument(0));

        SurveyDto dto = service.updateMetadata(id,
                new com.bvisionry.survey.dto.SurveyMetadataUpdateRequest(
                        survey.getName(), survey.getDescription(), SurveyVisibility.PRIVATE, null, null));

        assertThat(dto.visibility()).isEqualTo(SurveyVisibility.PRIVATE);
        assertThat(dto.publicToken()).isNull();
    }

    @Test
    void flipVisibilityToPublic_mintsPublicToken() {
        Survey survey = buildPublishedSurvey();
        survey.setVisibility(SurveyVisibility.PRIVATE);
        survey.setPublicToken(null);
        UUID id = survey.getId();
        when(surveyRepository.findById(id)).thenReturn(Optional.of(survey));
        when(surveyRepository.save(any(Survey.class))).thenAnswer(inv -> inv.getArgument(0));

        SurveyDto dto = service.updateMetadata(id,
                new com.bvisionry.survey.dto.SurveyMetadataUpdateRequest(
                        survey.getName(), survey.getDescription(), SurveyVisibility.PUBLIC, null, null));

        assertThat(dto.visibility()).isEqualTo(SurveyVisibility.PUBLIC);
        assertThat(dto.publicToken()).isNotNull();
    }

    @Test
    void publish_blockedWhenNoPillars() {
        Survey empty = new Survey();
        empty.setId(UUID.randomUUID());
        empty.setStatus(SurveyStatus.DRAFT);
        when(surveyRepository.findById(empty.getId())).thenReturn(Optional.of(empty));

        assertThatThrownBy(() -> service.transitionStatus(empty.getId(),
                new SurveyStatusRequest(SurveyStatus.PUBLISHED)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least 1 pillar");
    }

    @Test
    void close_setsClosedAt() {
        Survey survey = buildPublishedSurvey();
        when(surveyRepository.findById(survey.getId())).thenReturn(Optional.of(survey));
        when(surveyRepository.save(any(Survey.class))).thenAnswer(inv -> inv.getArgument(0));

        SurveyDto dto = service.transitionStatus(
                survey.getId(), new SurveyStatusRequest(SurveyStatus.CLOSED));

        assertThat(dto.status()).isEqualTo(SurveyStatus.CLOSED);
        assertThat(dto.closedAt()).isNotNull();
    }

    @Test
    void reopen_preservesPublicToken() {
        Survey closed = buildPublishedSurvey();
        closed.setStatus(SurveyStatus.CLOSED);
        UUID token = closed.getPublicToken();
        when(surveyRepository.findById(closed.getId())).thenReturn(Optional.of(closed));
        when(surveyRepository.save(any(Survey.class))).thenAnswer(inv -> inv.getArgument(0));

        SurveyDto dto = service.transitionStatus(
                closed.getId(), new SurveyStatusRequest(SurveyStatus.PUBLISHED));

        assertThat(dto.status()).isEqualTo(SurveyStatus.PUBLISHED);
        assertThat(dto.publicToken()).isEqualTo(token);
        assertThat(dto.closedAt()).isNull();
    }

    @Test
    void revertToDraft_blockedWhenResponsesExist() {
        Survey survey = buildPublishedSurvey();
        when(surveyRepository.findById(survey.getId())).thenReturn(Optional.of(survey));
        when(responseRepository.countBySurveyId(survey.getId())).thenReturn(1L);

        assertThatThrownBy(() -> service.transitionStatus(
                survey.getId(), new SurveyStatusRequest(SurveyStatus.DRAFT)))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("existing responses");
    }

    @Test
    void delete_blockedForPublishedSurvey() {
        Survey survey = buildPublishedSurvey();
        when(surveyRepository.findById(survey.getId())).thenReturn(Optional.of(survey));

        assertThatThrownBy(() -> service.delete(survey.getId()))
                .isInstanceOf(IllegalOperationException.class);
    }

    @Test
    void getById_notFound_throws() {
        UUID missing = UUID.randomUUID();
        when(surveyRepository.findById(missing)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.getById(missing))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    private Survey buildValidDraftSurvey() {
        Survey s = new Survey();
        s.setId(UUID.randomUUID());
        s.setStatus(SurveyStatus.DRAFT);
        SurveyPillar pillar = new SurveyPillar();
        pillar.setId(UUID.randomUUID());
        pillar.setSurvey(s);
        pillar.setName("Pillar 1");
        SurveyQuestion q = new SurveyQuestion();
        q.setId(UUID.randomUUID());
        q.setPillar(pillar);
        q.setType(SurveyQuestionType.SHORT_TEXT);
        q.setPromptText("Q");
        q.setConfigJson(Map.of());
        pillar.setQuestions(List.of(q));
        s.setPillars(List.of(pillar));
        return s;
    }

    private Survey buildPublishedSurvey() {
        Survey s = buildValidDraftSurvey();
        s.setStatus(SurveyStatus.PUBLISHED);
        s.setVisibility(SurveyVisibility.PUBLIC);
        s.setPublicToken(UUID.randomUUID());
        s.setPublishedAt(java.time.Instant.now());
        return s;
    }
}
