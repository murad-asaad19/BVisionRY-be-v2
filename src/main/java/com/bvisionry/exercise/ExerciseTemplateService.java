package com.bvisionry.exercise;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.exercise.dto.ExerciseColumnResponse;
import com.bvisionry.exercise.dto.ExerciseTemplateDetailResponse;
import com.bvisionry.exercise.dto.ExerciseTemplateResponse;
import com.bvisionry.exercise.dto.ReorderColumnsRequest;
import com.bvisionry.exercise.dto.UpsertExerciseColumnRequest;
import com.bvisionry.exercise.dto.UpsertExerciseTemplateRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.repository.ExerciseAssignmentRepository;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
import com.bvisionry.exercise.repository.ExerciseTemplateRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Super-admin authoring of exercise templates and their columns. Column
 * structure is frozen (no add/delete) once the template has assignments so
 * members' saved rows and comment anchors can't be invalidated; renames,
 * descriptions, config tweaks and reorders stay allowed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseTemplateService {

    private final ExerciseTemplateRepository templateRepository;
    private final ExerciseColumnRepository columnRepository;
    private final ExerciseAssignmentRepository assignmentRepository;

    @Transactional(readOnly = true)
    public List<ExerciseTemplateResponse> list(ExerciseTemplateStatus status) {
        List<ExerciseTemplate> templates = status != null
                ? templateRepository.findByStatusOrderByCreatedAtDesc(status)
                : templateRepository.findAllByOrderByCreatedAtDesc();

        Map<UUID, Integer> columnCounts = new HashMap<>();
        for (Object[] row : columnRepository.countAllGroupByTemplate()) {
            columnCounts.put((UUID) row[0], ((Long) row[1]).intValue());
        }
        Map<UUID, List<ExerciseTemplateResponse.AssignedOrg>> orgsByTemplate = new HashMap<>();
        for (Object[] row : assignmentRepository.findProvisionOrgsGroupByTemplate()) {
            orgsByTemplate.computeIfAbsent((UUID) row[0], k -> new ArrayList<>())
                    .add(new ExerciseTemplateResponse.AssignedOrg((UUID) row[1], (String) row[2]));
        }
        return templates.stream()
                .map(t -> ExerciseTemplateResponse.from(t,
                        columnCounts.getOrDefault(t.getId(), 0),
                        orgsByTemplate.getOrDefault(t.getId(), List.of())))
                .toList();
    }

    @Transactional(readOnly = true)
    public ExerciseTemplateDetailResponse get(UUID id) {
        return ExerciseTemplateDetailResponse.from(requireTemplateWithColumns(id), isStructureLocked(id));
    }

    @Transactional
    public ExerciseTemplateDetailResponse create(UpsertExerciseTemplateRequest request) {
        ExerciseTemplate template = new ExerciseTemplate();
        template.setName(request.name());
        template.setDescription(request.description());
        template.setCreatedBy(SecurityUtils.getCurrentUserId());
        return ExerciseTemplateDetailResponse.from(templateRepository.save(template), false);
    }

    @Transactional
    public ExerciseTemplateDetailResponse update(UUID id, UpsertExerciseTemplateRequest request) {
        ExerciseTemplate template = requireTemplateWithColumns(id);
        requireNotArchived(template);
        template.setName(request.name());
        template.setDescription(request.description());
        template.setExampleRow(request.exampleRow());
        template.setStarterRows(request.starterRows());
        template.setAllowAddRows(request.allowAddRows());
        return ExerciseTemplateDetailResponse.from(template, isStructureLocked(id));
    }

    @Transactional
    public void delete(UUID id) {
        ExerciseTemplate template = requireTemplate(id);
        if (assignmentRepository.countByTemplateId(id) > 0) {
            throw new BadRequestException(
                    "This exercise has been assigned and cannot be deleted. Archive it instead.");
        }
        templateRepository.delete(template);
        log.info("Deleted exercise template {}", id);
    }

    @Transactional
    public ExerciseTemplateDetailResponse updateStatus(UUID id, ExerciseTemplateStatus target) {
        ExerciseTemplate template = requireTemplateWithColumns(id);
        ExerciseTemplateStatus current = template.getStatus();
        boolean allowed = switch (target) {
            case PUBLISHED -> current == ExerciseTemplateStatus.DRAFT
                    || current == ExerciseTemplateStatus.ARCHIVED;
            case ARCHIVED -> current == ExerciseTemplateStatus.PUBLISHED;
            case DRAFT -> false;
        };
        if (!allowed) {
            throw new BadRequestException(
                    "Cannot move an exercise from " + current + " to " + target + ".");
        }
        if (target == ExerciseTemplateStatus.PUBLISHED && template.getColumns().isEmpty()) {
            throw new BadRequestException("Add at least one column before publishing.");
        }
        template.setStatus(target);
        return ExerciseTemplateDetailResponse.from(template, isStructureLocked(id));
    }

    @Transactional
    public ExerciseColumnResponse addColumn(UUID templateId, UpsertExerciseColumnRequest request) {
        ExerciseTemplate template = requireTemplate(templateId);
        requireNotArchived(template);
        requireStructureEditable(templateId);

        ExerciseColumn column = new ExerciseColumn();
        column.setTemplate(template);
        applyColumn(column, request);
        column.setDisplayOrder(columnRepository.countByTemplateId(templateId));
        return ExerciseColumnResponse.from(columnRepository.save(column));
    }

    @Transactional
    public ExerciseColumnResponse updateColumn(UUID templateId, UUID columnId,
                                               UpsertExerciseColumnRequest request) {
        ExerciseColumn column = requireColumnInTemplate(templateId, columnId);
        requireNotArchived(column.getTemplate());
        applyColumn(column, request);
        return ExerciseColumnResponse.from(column);
    }

    @Transactional
    public void deleteColumn(UUID templateId, UUID columnId) {
        ExerciseColumn column = requireColumnInTemplate(templateId, columnId);
        requireNotArchived(column.getTemplate());
        requireStructureEditable(templateId);
        columnRepository.delete(column);

        // Close the ordering gap so display_order stays dense.
        List<ExerciseColumn> remaining = columnRepository.findByTemplateIdOrderByDisplayOrder(templateId);
        for (int i = 0; i < remaining.size(); i++) {
            remaining.get(i).setDisplayOrder(i);
        }
    }

    @Transactional
    public List<ExerciseColumnResponse> reorderColumns(UUID templateId, ReorderColumnsRequest request) {
        requireNotArchived(requireTemplate(templateId));
        List<ExerciseColumn> columns = columnRepository.findByTemplateIdOrderByDisplayOrder(templateId);
        Map<UUID, ExerciseColumn> byId = new HashMap<>();
        columns.forEach(c -> byId.put(c.getId(), c));

        Set<UUID> requested = new HashSet<>(request.columnIds());
        if (requested.size() != request.columnIds().size() || !requested.equals(byId.keySet())) {
            throw new BadRequestException(
                    "columnIds must contain every column of this exercise exactly once.");
        }
        for (int i = 0; i < request.columnIds().size(); i++) {
            byId.get(request.columnIds().get(i)).setDisplayOrder(i);
        }
        return columnRepository.findByTemplateIdOrderByDisplayOrder(templateId).stream()
                .map(ExerciseColumnResponse::from)
                .toList();
    }

    private void applyColumn(ExerciseColumn column, UpsertExerciseColumnRequest request) {
        column.setName(request.name());
        column.setDescription(request.description());
        column.setType(request.type());
        column.setConfigJson(request.configJson());
        column.setRequired(request.isRequired());
        column.setLocked(request.isLocked());
    }

    /**
     * Adding or removing columns after members hold data against them would
     * orphan cell values and comment anchors, so structure is frozen once any
     * assignment (provision or member) exists.
     */
    private boolean isStructureLocked(UUID templateId) {
        return assignmentRepository.countByTemplateId(templateId) > 0;
    }

    private void requireNotArchived(ExerciseTemplate template) {
        if (template.getStatus() == ExerciseTemplateStatus.ARCHIVED) {
            throw new BadRequestException(
                    "This exercise is archived and can no longer be edited. Republish it to make changes.");
        }
    }

    private void requireStructureEditable(UUID templateId) {
        if (assignmentRepository.countByTemplateId(templateId) > 0) {
            throw new BadRequestException(
                    "This exercise has been assigned — columns can no longer be added or removed.");
        }
    }

    private ExerciseTemplate requireTemplate(UUID id) {
        return templateRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Exercise", id.toString()));
    }

    private ExerciseTemplate requireTemplateWithColumns(UUID id) {
        return templateRepository.findByIdWithColumns(id)
                .orElseThrow(() -> new ResourceNotFoundException("Exercise", id.toString()));
    }

    private ExerciseColumn requireColumnInTemplate(UUID templateId, UUID columnId) {
        ExerciseColumn column = columnRepository.findById(columnId)
                .orElseThrow(() -> new ResourceNotFoundException("Column", columnId.toString()));
        if (!column.getTemplate().getId().equals(templateId)) {
            throw new ResourceNotFoundException("Column", columnId.toString());
        }
        return column;
    }
}
