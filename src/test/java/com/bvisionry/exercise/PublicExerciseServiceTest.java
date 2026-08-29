package com.bvisionry.exercise;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.exercise.dto.PublicExerciseSubmitRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseColumnType;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.entity.PublicExerciseResponse;
import com.bvisionry.exercise.entity.RespondentFieldMode;
import com.bvisionry.exercise.entity.WorksheetBlock;
import com.bvisionry.exercise.entity.WorksheetBlockType;
import com.bvisionry.exercise.repository.ExerciseTemplateRepository;
import com.bvisionry.exercise.repository.PublicExerciseResponseRepository;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.entity.SurveyVisibility;
import com.bvisionry.survey.repository.SurveyRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
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
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PublicExerciseServiceTest {

    @Mock private ExerciseTemplateRepository templateRepository;
    @Mock private PublicExerciseResponseRepository responseRepository;
    @Mock private SurveyRepository surveyRepository;
    @Mock private MediaUrlPort mediaUrlPort;

    @InjectMocks private PublicExerciseService service;

    private UUID token;
    private ExerciseTemplate template;

    @BeforeEach
    void setUp() {
        token = UUID.randomUUID();
        template = new ExerciseTemplate();
        template.setId(UUID.randomUUID());
        template.setName("Runway map");
        template.setStatus(ExerciseTemplateStatus.PUBLISHED);
        template.setPublic(true);
        template.setPublicToken(token);
        template.setRespondentNameMode(RespondentFieldMode.OPTIONAL);
        template.setRespondentEmailMode(RespondentFieldMode.OPTIONAL);
        lenient().when(templateRepository.findByPublicTokenWithColumns(token))
                .thenReturn(Optional.of(template));
        lenient().when(responseRepository.save(any(PublicExerciseResponse.class)))
                .thenAnswer(call -> {
                    PublicExerciseResponse saved = call.getArgument(0);
                    saved.setId(UUID.randomUUID());
                    return saved;
                });
    }

    private ExerciseColumn column(String name, boolean required, boolean locked) {
        ExerciseColumn column = new ExerciseColumn();
        column.setId(UUID.randomUUID());
        column.setName(name);
        column.setType(ExerciseColumnType.TEXT);
        column.setRequired(required);
        column.setLocked(locked);
        column.setTemplate(template);
        template.getColumns().add(column);
        return column;
    }

    private WorksheetBlock block(WorksheetBlockType type, boolean required) {
        WorksheetBlock block = new WorksheetBlock(UUID.randomUUID(), type, "Prompt", required, Map.of());
        template.setKind(ExerciseTemplateKind.WORKSHEET);
        template.setBlocks(List.of(block));
        return block;
    }

    private PublicExerciseResponse captureSaved() {
        ArgumentCaptor<PublicExerciseResponse> captor =
                ArgumentCaptor.forClass(PublicExerciseResponse.class);
        verify(responseRepository).save(captor.capture());
        return captor.getValue();
    }

    private PublicExerciseSubmitRequest sheetSubmit(List<Map<String, Object>> rows) {
        return new PublicExerciseSubmitRequest(null, null, null, rows);
    }

    // ---- the token gates -------------------------------------------------

    @Test
    void getByToken_unknownToken_is404() {
        UUID other = UUID.randomUUID();
        when(templateRepository.findByPublicTokenWithColumns(other)).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.getByToken(other))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByToken_linkClosed_is404() {
        template.setPublic(false);

        assertThatThrownBy(() -> service.getByToken(token))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getByToken_exerciseArchived_is404() {
        // Archiving must close the public link without anyone remembering to
        // flip is_public — a live QR pointing at a withdrawn exercise is the
        // whole risk here.
        template.setStatus(ExerciseTemplateStatus.ARCHIVED);

        assertThatThrownBy(() -> service.getByToken(token))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void submit_afterTheLinkIsClosed_isRejected() {
        column("Idea", false, false);
        template.setPublic(false);

        assertThatThrownBy(() -> service.submit(token,
                sheetSubmit(List.of(Map.of())), "hash", "agent"))
                .isInstanceOf(ResourceNotFoundException.class);
        verify(responseRepository, never()).save(any());
    }

    // ---- respondent fields ----------------------------------------------

    @Test
    void submit_requiredEmailMissing_isRejected() {
        column("Idea", false, false);
        template.setRespondentEmailMode(RespondentFieldMode.REQUIRED);

        assertThatThrownBy(() -> service.submit(token,
                new PublicExerciseSubmitRequest("Sam", "  ", null, List.of(Map.of())),
                "hash", "agent"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("email address is required");
    }

    @Test
    void submit_fieldsSetToNone_areNotStoredEvenIfSent() {
        ExerciseColumn idea = column("Idea", false, false);
        template.setRespondentNameMode(RespondentFieldMode.NONE);
        template.setRespondentEmailMode(RespondentFieldMode.NONE);

        service.submit(token, new PublicExerciseSubmitRequest("  Sam  ", "sam@example.com",
                null, List.of(Map.of(idea.getId().toString(), "Ship it"))), "hash", "agent");

        PublicExerciseResponse saved = captureSaved();
        assertThat(saved.getRespondentName()).isNull();
        assertThat(saved.getRespondentEmail()).isNull();
    }

    @Test
    void submit_trimsTheRespondentName() {
        ExerciseColumn idea = column("Idea", false, false);

        service.submit(token, new PublicExerciseSubmitRequest("  Sam  ", null,
                null, List.of(Map.of(idea.getId().toString(), "Ship it"))), "hash", "agent");

        assertThat(captureSaved().getRespondentName()).isEqualTo("Sam");
    }

    // ---- sheets ----------------------------------------------------------

    @Test
    void submit_sheetWithNoRows_isRejected() {
        column("Idea", false, false);

        assertThatThrownBy(() -> service.submit(token, sheetSubmit(List.of()), "hash", "agent"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at least one row");
    }

    @Test
    void submit_sheetMissingARequiredCell_isRejected() {
        ExerciseColumn idea = column("Idea", true, false);

        assertThatThrownBy(() -> service.submit(token,
                sheetSubmit(List.of(Map.of(idea.getId().toString(), "  "))), "hash", "agent"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("\"Idea\" is required");
    }

    @Test
    void submit_sheetDropsUnknownColumnsAndBlankRows() {
        ExerciseColumn idea = column("Idea", false, false);

        service.submit(token, sheetSubmit(List.of(
                Map.of(idea.getId().toString(), "Ship it", UUID.randomUUID().toString(), "junk"),
                Map.of())), "hash", "agent");

        assertThat(captureSaved().getSheetRows())
                .containsExactly(Map.of(idea.getId().toString(), "Ship it"));
    }

    @Test
    void submit_sheetRestoresLockedCellsFromTheStarterRows() {
        ExerciseColumn round = column("Round", false, true);
        ExerciseColumn idea = column("Idea", false, false);
        template.setStarterRows(List.of(Map.of(round.getId().toString(), "Round 1")));

        service.submit(token, sheetSubmit(List.of(Map.of(
                round.getId().toString(), "Round 99",
                idea.getId().toString(), "Ship it"))), "hash", "agent");

        assertThat(captureSaved().getSheetRows()).containsExactly(Map.of(
                round.getId().toString(), "Round 1",
                idea.getId().toString(), "Ship it"));
    }

    @Test
    void submit_sheetThatForbidsAddedRows_rejectsExtraRows() {
        ExerciseColumn idea = column("Idea", false, false);
        template.setAllowAddRows(false);
        template.setStarterRows(List.of(Map.of()));

        assertThatThrownBy(() -> service.submit(token, sheetSubmit(List.of(
                Map.of(idea.getId().toString(), "One"),
                Map.of(idea.getId().toString(), "Two"))), "hash", "agent"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("does not allow adding rows");
    }

    @Test
    void submit_sheetOverTheRowCeiling_isRejected() {
        ExerciseColumn idea = column("Idea", false, false);
        List<Map<String, Object>> rows = java.util.stream.IntStream.range(0, 201)
                .<Map<String, Object>>mapToObj(i -> Map.of(idea.getId().toString(), "row " + i))
                .toList();

        assertThatThrownBy(() -> service.submit(token, sheetSubmit(rows), "hash", "agent"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("at most 200 rows");
    }

    // ---- worksheets ------------------------------------------------------

    @Test
    void submit_worksheetMissingARequiredBlock_isRejected() {
        block(WorksheetBlockType.TEXT, true);

        assertThatThrownBy(() -> service.submit(token,
                new PublicExerciseSubmitRequest(null, null, Map.of(), null), "hash", "agent"))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void submit_worksheetKeepsOnlyWellShapedAnswers() {
        WorksheetBlock text = block(WorksheetBlockType.TEXT, true);

        service.submit(token, new PublicExerciseSubmitRequest(null, null, Map.of(
                text.id().toString(), "An answer",
                UUID.randomUUID().toString(), "for a block that does not exist"), null),
                "hash", "agent");

        assertThat(captureSaved().getAnswers())
                .containsExactly(Map.entry(text.id().toString(), "An answer"));
    }

    @Test
    void submit_sheetOverTheContentCeiling_isRejected() {
        ExerciseColumn idea = column("Idea", false, false);

        assertThatThrownBy(() -> service.submit(token,
                sheetSubmit(List.of(Map.of(idea.getId().toString(), "x".repeat(200_001)))),
                "hash", "agent"))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("too long");
        verify(responseRepository, never()).save(any());
    }

    @Test
    void submit_cutsAnOverlongUserAgentToTheColumnWidth() {
        // A client sets this header; storing it raw blows the VARCHAR(512) and
        // loses the whole fill to a 500 at insert time.
        ExerciseColumn idea = column("Idea", false, false);

        service.submit(token, sheetSubmit(List.of(Map.of(idea.getId().toString(), "Ship it"))),
                "hash", "U".repeat(9000));

        assertThat(captureSaved().getUserAgent()).hasSize(512);
    }

    @Test
    void submit_recordsTheForensicsFields() {
        ExerciseColumn idea = column("Idea", false, false);

        service.submit(token, sheetSubmit(List.of(Map.of(idea.getId().toString(), "Ship it"))),
                "the-ip-hash", "Mozilla/5.0");

        PublicExerciseResponse saved = captureSaved();
        assertThat(saved.getIpHash()).isEqualTo("the-ip-hash");
        assertThat(saved.getUserAgent()).isEqualTo("Mozilla/5.0");
        assertThat(saved.getSubmittedAt()).isNotNull();
        assertThat(saved.getTemplate()).isSameAs(template);
    }

    // ---- the post-completion survey CTA ---------------------------------

    private Survey pairedSurvey(SurveyStatus status, SurveyVisibility visibility, UUID publicToken) {
        UUID surveyId = UUID.randomUUID();
        template.setPostCompletionSurveyId(surveyId);
        Survey survey = new Survey();
        survey.setId(surveyId);
        survey.setName("Founder Mindset Pulse");
        survey.setStatus(status);
        survey.setVisibility(visibility);
        survey.setPublicToken(publicToken);
        lenient().when(surveyRepository.findById(surveyId)).thenReturn(Optional.of(survey));
        return survey;
    }

    @Test
    void getByToken_noSurveyPaired_offersNoCta() {
        assertThat(service.getByToken(token).postCompletionSurvey()).isNull();
    }

    @Test
    void getByToken_publishedPublicSurvey_isOfferedByItsPublicToken() {
        UUID surveyToken = UUID.randomUUID();
        pairedSurvey(SurveyStatus.PUBLISHED, SurveyVisibility.PUBLIC, surveyToken);

        var cta = service.getByToken(token).postCompletionSurvey();

        assertThat(cta).isNotNull();
        assertThat(cta.token()).isEqualTo(surveyToken);
        assertThat(cta.name()).isEqualTo("Founder Mindset Pulse");
    }

    @Test
    void getByToken_privateSurvey_offersNoCta() {
        // A PRIVATE survey has no link an anonymous respondent can open, so the
        // CTA must disappear rather than point at a 404.
        pairedSurvey(SurveyStatus.PUBLISHED, SurveyVisibility.PRIVATE, UUID.randomUUID());

        assertThat(service.getByToken(token).postCompletionSurvey()).isNull();
    }

    @Test
    void getByToken_unpublishedSurvey_offersNoCta() {
        pairedSurvey(SurveyStatus.DRAFT, SurveyVisibility.PUBLIC, UUID.randomUUID());

        assertThat(service.getByToken(token).postCompletionSurvey()).isNull();
    }

    @Test
    void getByToken_surveyWithNoMintedToken_offersNoCta() {
        pairedSurvey(SurveyStatus.PUBLISHED, SurveyVisibility.PUBLIC, null);

        assertThat(service.getByToken(token).postCompletionSurvey()).isNull();
    }

    @Test
    void getByToken_pairedSurveyDeleted_offersNoCta() {
        UUID surveyId = UUID.randomUUID();
        template.setPostCompletionSurveyId(surveyId);
        when(surveyRepository.findById(surveyId)).thenReturn(Optional.empty());

        assertThat(service.getByToken(token).postCompletionSurvey()).isNull();
    }
}
