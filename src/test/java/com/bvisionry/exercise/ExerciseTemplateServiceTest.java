package com.bvisionry.exercise;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.exercise.dto.UpdatePublicExerciseRequest;
import com.bvisionry.exercise.dto.UpsertExerciseColumnRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseColumnType;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.entity.RespondentFieldMode;
import com.bvisionry.exercise.repository.ExerciseAssignmentRepository;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
import com.bvisionry.exercise.repository.ExerciseTemplateRepository;
import com.bvisionry.exercise.repository.PublicExerciseResponseRepository;
import com.bvisionry.common.surveylink.PublicSurveyLinkPort;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ExerciseTemplateServiceTest {

    @Mock private ExerciseTemplateRepository templateRepository;
    @Mock private ExerciseColumnRepository columnRepository;
    @Mock private ExerciseAssignmentRepository assignmentRepository;
    @Mock private PublicExerciseResponseRepository publicResponseRepository;
    @Mock private PublicSurveyLinkPort publicSurveyLinkPort;
    @Mock private MediaUrlPort mediaUrlPort;

    @InjectMocks private ExerciseTemplateService service;

    private UUID templateId;
    private UUID columnId;
    private ExerciseColumn column;
    private ExerciseTemplate template;

    @BeforeEach
    void setUp() {
        templateId = UUID.randomUUID();
        columnId = UUID.randomUUID();

        template = new ExerciseTemplate();
        template.setId(templateId);
        template.setStatus(ExerciseTemplateStatus.PUBLISHED);

        column = new ExerciseColumn();
        column.setId(columnId);
        column.setName("Answer");
        column.setType(ExerciseColumnType.TEXT);
        column.setLocked(false);
        column.setTemplate(template);

        lenient().when(columnRepository.findById(columnId)).thenReturn(Optional.of(column));
        lenient().when(templateRepository.findByIdWithColumns(templateId))
                .thenReturn(Optional.of(template));
    }

    private UpsertExerciseColumnRequest request(String name, ExerciseColumnType type, boolean locked) {
        return new UpsertExerciseColumnRequest(name, null, type, null, false, locked);
    }

    @Test
    void updateColumn_typeChangeAfterAssignment_isRejected() {
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateColumn(templateId, columnId,
                request("Answer", ExerciseColumnType.NUMBER, false)))
                .isInstanceOf(BadRequestException.class);
        assertThat(column.getType()).isEqualTo(ExerciseColumnType.TEXT);
    }

    @Test
    void updateColumn_lockFlipAfterAssignment_isRejected() {
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateColumn(templateId, columnId,
                request("Answer", ExerciseColumnType.TEXT, true)))
                .isInstanceOf(BadRequestException.class);
        assertThat(column.isLocked()).isFalse();
    }

    @Test
    void updateColumn_renameAfterAssignment_isAllowed() {
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(1L);

        service.updateColumn(templateId, columnId,
                request("Renamed", ExerciseColumnType.TEXT, false));

        assertThat(column.getName()).isEqualTo("Renamed");
    }

    // ---------------------------------------------------------------------
    // Lossless type changes stay open after assignment: TEXT and LONG_TEXT
    // both store a plain string, and either widens into LIST because the web
    // reads a bare string as the list's first entry.
    // ---------------------------------------------------------------------

    @Test
    void updateColumn_textToLongTextAfterAssignment_isAllowed() {
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(1L);

        service.updateColumn(templateId, columnId,
                request("Answer", ExerciseColumnType.LONG_TEXT, false));

        assertThat(column.getType()).isEqualTo(ExerciseColumnType.LONG_TEXT);
    }

    @Test
    void updateColumn_longTextToTextAfterAssignment_isAllowed() {
        column.setType(ExerciseColumnType.LONG_TEXT);
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(1L);

        service.updateColumn(templateId, columnId,
                request("Answer", ExerciseColumnType.TEXT, false));

        assertThat(column.getType()).isEqualTo(ExerciseColumnType.TEXT);
    }

    @Test
    void updateColumn_longTextToListAfterAssignment_isAllowed() {
        column.setType(ExerciseColumnType.LONG_TEXT);
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(1L);

        service.updateColumn(templateId, columnId,
                request("Answer", ExerciseColumnType.LIST, false));

        assertThat(column.getType()).isEqualTo(ExerciseColumnType.LIST);
    }

    @Test
    void updateColumn_listCannotNarrowBackAfterAssignment() {
        // A multi-entry cell has no single value to collapse into, and comments
        // anchored to an entry id would lose their target.
        column.setType(ExerciseColumnType.LIST);
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateColumn(templateId, columnId,
                request("Answer", ExerciseColumnType.TEXT, false)))
                .isInstanceOf(BadRequestException.class);
        assertThat(column.getType()).isEqualTo(ExerciseColumnType.LIST);
    }

    @Test
    void updateColumn_dateToTextAfterAssignment_isRejected() {
        // Only TEXT/LONG_TEXT are interchangeable — widening from a stricter
        // type is not automatically safe and stays frozen.
        column.setType(ExerciseColumnType.DATE);
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(1L);

        assertThatThrownBy(() -> service.updateColumn(templateId, columnId,
                request("Answer", ExerciseColumnType.TEXT, false)))
                .isInstanceOf(BadRequestException.class);
        assertThat(column.getType()).isEqualTo(ExerciseColumnType.DATE);
    }

    @Test
    void updateColumn_textToListBeforeAssignment_isAllowed() {
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(0L);

        service.updateColumn(templateId, columnId,
                request("Answer", ExerciseColumnType.LIST, false));

        assertThat(column.getType()).isEqualTo(ExerciseColumnType.LIST);
    }

    private static UpdatePublicExerciseRequest publicRequest(boolean isPublic) {
        return new UpdatePublicExerciseRequest(isPublic,
                RespondentFieldMode.OPTIONAL, RespondentFieldMode.OPTIONAL, null);
    }

    @Test
    void updatePublicSettings_openingADraft_isRejected() {
        template.setStatus(ExerciseTemplateStatus.DRAFT);

        assertThatThrownBy(() -> service.updatePublicSettings(templateId, publicRequest(true)))
                .isInstanceOf(BadRequestException.class);
        assertThat(template.getPublicToken()).isNull();
        assertThat(template.isPublic()).isFalse();
    }

    @Test
    void updatePublicSettings_mintsTheTokenOnceAndKeepsItWhenClosed() {
        service.updatePublicSettings(templateId, publicRequest(true));
        UUID minted = template.getPublicToken();
        assertThat(minted).isNotNull();

        // Closing and reopening must NOT re-mint: the first token is already
        // printed on QR codes by then.
        service.updatePublicSettings(templateId, publicRequest(false));
        assertThat(template.isPublic()).isFalse();
        assertThat(template.getPublicToken()).isEqualTo(minted);

        service.updatePublicSettings(templateId, publicRequest(true));
        assertThat(template.getPublicToken()).isEqualTo(minted);
    }

    @Test
    void updatePublicSettings_pairingASurveyThatIsGone_is404NotAConstraintViolation() {
        // The console re-sends the whole settings block on every control, so a
        // survey deleted since the page loaded arrives on the NEXT unrelated
        // toggle. The FK would make that a 500 and brick the card.
        UUID deleted = UUID.randomUUID();
        when(publicSurveyLinkPort.exists(deleted)).thenReturn(false);

        assertThatThrownBy(() -> service.updatePublicSettings(templateId,
                new UpdatePublicExerciseRequest(true, RespondentFieldMode.OPTIONAL,
                        RespondentFieldMode.OPTIONAL, deleted)))
                .isInstanceOf(ResourceNotFoundException.class);
        assertThat(template.getPostCompletionSurveyId()).isNull();
    }

    @Test
    void updatePublicSettings_pairingALiveSurvey_isStored() {
        UUID surveyId = UUID.randomUUID();
        when(publicSurveyLinkPort.exists(surveyId)).thenReturn(true);

        service.updatePublicSettings(templateId, new UpdatePublicExerciseRequest(
                true, RespondentFieldMode.OPTIONAL, RespondentFieldMode.OPTIONAL, surveyId));

        assertThat(template.getPostCompletionSurveyId()).isEqualTo(surveyId);
    }

    @Test
    void delete_withCollectedPublicResponses_isRejected() {
        lenient().when(templateRepository.findById(templateId))
                .thenReturn(Optional.of(template));
        lenient().when(assignmentRepository.countByTemplateId(templateId)).thenReturn(0L);
        lenient().when(publicResponseRepository.countByTemplateId(templateId)).thenReturn(3L);

        assertThatThrownBy(() -> service.delete(templateId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("collected public responses");
    }
}
