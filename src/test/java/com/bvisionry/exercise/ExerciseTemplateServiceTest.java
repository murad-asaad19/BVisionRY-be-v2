package com.bvisionry.exercise;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.exercise.dto.UpsertExerciseColumnRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseColumnType;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.repository.ExerciseAssignmentRepository;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
import com.bvisionry.exercise.repository.ExerciseTemplateRepository;
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

@ExtendWith(MockitoExtension.class)
class ExerciseTemplateServiceTest {

    @Mock private ExerciseTemplateRepository templateRepository;
    @Mock private ExerciseColumnRepository columnRepository;
    @Mock private ExerciseAssignmentRepository assignmentRepository;

    @InjectMocks private ExerciseTemplateService service;

    private UUID templateId;
    private UUID columnId;
    private ExerciseColumn column;

    @BeforeEach
    void setUp() {
        templateId = UUID.randomUUID();
        columnId = UUID.randomUUID();

        ExerciseTemplate template = new ExerciseTemplate();
        template.setId(templateId);
        template.setStatus(ExerciseTemplateStatus.PUBLISHED);

        column = new ExerciseColumn();
        column.setId(columnId);
        column.setName("Answer");
        column.setType(ExerciseColumnType.TEXT);
        column.setLocked(false);
        column.setTemplate(template);

        lenient().when(columnRepository.findById(columnId)).thenReturn(Optional.of(column));
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
}
