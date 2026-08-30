package com.bvisionry.exercise;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.exercise.dto.ExerciseColumnResponse;
import com.bvisionry.exercise.dto.ExercisePlacementsResponse;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.exercise.dto.ExerciseTemplateDetailResponse;
import com.bvisionry.exercise.dto.ExerciseTemplateResponse;
import com.bvisionry.exercise.dto.ReorderColumnsRequest;
import com.bvisionry.exercise.dto.UpsertExerciseColumnRequest;
import com.bvisionry.exercise.dto.UpdatePublicExerciseRequest;
import com.bvisionry.exercise.dto.UpsertExerciseTemplateRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.repository.ExerciseAssignmentRepository;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
import com.bvisionry.exercise.repository.ExercisePlacementRepository;
import com.bvisionry.exercise.repository.ExerciseTemplateRepository;
import com.bvisionry.common.surveylink.PublicSurveyLinkPort;
import com.bvisionry.exercise.repository.PublicExerciseResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Super-admin authoring of exercise templates and their columns. Column
 * structure is frozen (no add/delete, no type or locked-state changes) once
 * the template has assignments so members' saved rows and comment anchors
 * can't be invalidated; renames, descriptions, config tweaks and reorders
 * stay allowed.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ExerciseTemplateService {

    private final CurrentUserAccessor currentUser;
    private final ExerciseTemplateRepository templateRepository;
    private final ExerciseColumnRepository columnRepository;
    private final ExerciseAssignmentRepository assignmentRepository;
    private final ExercisePlacementRepository placementRepository;
    private final PublicExerciseResponseRepository publicResponseRepository;
    /**
     * Only to prove a paired survey exists before storing its id. V211 makes
     * that column a real FK, so an id that no longer resolves is a constraint
     * violation at flush (a 500) instead of an answerable 404 — the same check
     * {@code PipelineService.setPostCompletion} runs on the column V211 copied.
     * Through the shared kernel, like the twin reach in
     * {@link PublicExerciseService}.
     */
    private final PublicSurveyLinkPort publicSurveyLinkPort;
    private final MediaUrlPort mediaUrlPort;

    @Transactional(readOnly = true)
    public List<ExerciseTemplateResponse> list(ExerciseTemplateStatus status) {
        List<ExerciseTemplate> templates = status != null
                ? templateRepository.findByStatusOrderByCreatedAtDesc(status)
                : templateRepository.findAllByOrderByCreatedAtDesc();

        Map<UUID, Integer> columnCounts = new HashMap<>();
        for (Object[] row : columnRepository.countAllGroupByTemplate()) {
            columnCounts.put((UUID) row[0], ((Long) row[1]).intValue());
        }
        Map<UUID, Integer> placements = placementRepository.countByTemplate();
        Set<UUID> undeletable = placementRepository.undeletableTemplateIds();
        return templates.stream()
                .map(t -> ExerciseTemplateResponse.from(t,
                        t.getKind() == ExerciseTemplateKind.WORKSHEET
                                ? (t.getBlocks() == null ? 0 : t.getBlocks().size())
                                : columnCounts.getOrDefault(t.getId(), 0),
                        placements.getOrDefault(t.getId(), 0) + (t.isPublic() ? 1 : 0),
                        !undeletable.contains(t.getId())))
                .toList();
    }

    /** Every place this template is handed out — the list's "assigned to" button. */
    @Transactional(readOnly = true)
    public ExercisePlacementsResponse placements(UUID id) {
        ExerciseTemplate template = requireTemplate(id);
        return new ExercisePlacementsResponse(
                placementRepository.organizations(id),
                placementRepository.cohorts(id),
                template.isPublic());
    }

    @Transactional(readOnly = true)
    public ExerciseTemplateDetailResponse get(UUID id) {
        return detail(requireTemplateWithColumns(id), isStructureLocked(id));
    }

    @Transactional
    public ExerciseTemplateDetailResponse create(UpsertExerciseTemplateRequest request) {
        ExerciseTemplate template = new ExerciseTemplate();
        template.setName(request.name());
        template.setKind(request.kind());
        if (request.kind() == ExerciseTemplateKind.WORKSHEET) {
            WorksheetBlocks.validate(request.blocks());
            template.setBlocks(request.blocks());
        }
        template.setDescription(request.description());
        template.setCoverImageUrl(request.coverImageUrl());
        template.setAiContext(request.aiContext());
        template.setCreatedBy(currentUser.require().userId());
        return detail(templateRepository.save(template), false);
    }

    @Transactional
    public ExerciseTemplateDetailResponse update(UUID id, UpsertExerciseTemplateRequest request) {
        ExerciseTemplate template = requireTemplateWithColumns(id);
        requireNotArchived(template);
        template.setName(request.name());
        // Kind is immutable (the request's kind field is create-only). Blocks
        // are a replace-all write, frozen to the same ids+types once assigned;
        // a null means "untouched" (an empty list is the explicit clear), so a
        // details-only save can never wipe the block list.
        if (template.getKind() == ExerciseTemplateKind.WORKSHEET && request.blocks() != null) {
            WorksheetBlocks.validate(request.blocks());
            if (isStructureLocked(id)) {
                WorksheetBlocks.requireStructureCompatible(template.getBlocks(), request.blocks());
            }
            template.setBlocks(request.blocks());
        }
        template.setDescription(request.description());
        template.setCoverImageUrl(request.coverImageUrl());
        template.setAiContext(request.aiContext());
        template.setExampleRow(request.exampleRow());
        template.setStarterRows(request.starterRows());
        template.setAllowAddRows(request.allowAddRows());
        return detail(template, isStructureLocked(id));
    }

    /**
     * The single place a template becomes a detail response, so the cover
     * marker is resolved exactly once and no new caller can ship a raw
     * {@code minio://} URL to the browser.
     */
    private ExerciseTemplateDetailResponse detail(ExerciseTemplate template, boolean structureLocked) {
        return ExerciseTemplateDetailResponse.from(template, structureLocked,
                mediaUrlPort.resolveUrl(template.getCoverImageUrl()),
                publicResponseRepository.countByTemplateId(template.getId()));
    }

    /**
     * Opens or closes the exercise's public link, and sets what it asks
     * respondents for.
     *
     * <p>Opening requires a PUBLISHED exercise — a draft has nothing worth
     * scanning a QR for. The token is minted on the first open and then left
     * alone: closing the link, archiving the exercise, or republishing it must
     * not invalidate a QR that is already printed on something.
     */
    @Transactional
    public ExerciseTemplateDetailResponse updatePublicSettings(UUID id,
                                                               UpdatePublicExerciseRequest request) {
        ExerciseTemplate template = requireTemplateWithColumns(id);
        boolean open = Boolean.TRUE.equals(request.isPublic());
        if (open) {
            if (template.getStatus() != ExerciseTemplateStatus.PUBLISHED) {
                throw new BadRequestException("Publish the exercise before opening a public link.");
            }
            if (template.getPublicToken() == null) {
                template.setPublicToken(UUID.randomUUID());
            }
        }
        template.setPublic(open);
        template.setRespondentNameMode(request.respondentNameMode());
        template.setRespondentEmailMode(request.respondentEmailMode());
        // Null unpairs. Whether the paired survey is actually REACHABLE by an
        // anonymous respondent is decided at read time in PublicExerciseService,
        // not here: a survey can be unpublished or made private long after it
        // was paired, and the pairing must survive that rather than vanish.
        // EXISTENCE is different, and is settled here: the column is an FK, so
        // a deleted survey's id would fail at flush as a 500. The console echoes
        // the whole settings block on every control, so one deleted survey would
        // otherwise brick the card until the admin reloaded the page.
        UUID surveyId = request.postCompletionSurveyId();
        if (surveyId != null && !publicSurveyLinkPort.exists(surveyId)) {
            throw new ResourceNotFoundException("Survey", surveyId.toString());
        }
        template.setPostCompletionSurveyId(surveyId);
        return detail(template, isStructureLocked(id));
    }

    @Transactional
    public void delete(UUID id) {
        ExerciseTemplate template = requireTemplate(id);
        if (assignmentRepository.countByTemplateId(id) > 0) {
            throw new IllegalOperationException(
                    "This exercise has been assigned and cannot be deleted. Archive it instead.");
        }
        // Public responses cascade with the template (V210). They are collected
        // answers from real people with no other copy anywhere, so a delete that
        // would take them is refused rather than silently destroying them.
        if (publicResponseRepository.countByTemplateId(id) > 0) {
            throw new IllegalOperationException(
                    "This exercise has collected public responses and cannot be deleted. "
                            + "Archive it instead.");
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
        if (target == ExerciseTemplateStatus.PUBLISHED) {
            if (template.getKind() == ExerciseTemplateKind.WORKSHEET) {
                if (template.getBlocks() == null || template.getBlocks().isEmpty()) {
                    throw new BadRequestException("Add at least one block before publishing.");
                }
            } else if (template.getColumns().isEmpty()) {
                throw new BadRequestException("Add at least one column before publishing.");
            }
        }
        template.setStatus(target);
        return detail(template, isStructureLocked(id));
    }

    @Transactional
    public ExerciseColumnResponse addColumn(UUID templateId, UpsertExerciseColumnRequest request) {
        ExerciseTemplate template = requireTemplate(templateId);
        requireSheet(template);
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
        // Once assigned, a column may only take a type that leaves every cell
        // members already filled still readable — see
        // ExerciseColumnType.convertsLosslesslyTo. Locked-state changes stay
        // frozen (they would freeze or release data mid-flight), as do
        // add/delete. Renames, descriptions, config tweaks and required are
        // always allowed.
        if (isStructureLocked(templateId)) {
            if (column.isLocked() != request.isLocked()) {
                throw new BadRequestException(
                        "This exercise has been assigned — a column's locked state can no longer change.");
            }
            if (!column.getType().convertsLosslesslyTo(request.type())) {
                throw new BadRequestException(
                        "This exercise has been assigned — a " + column.getType() + " column cannot become "
                                + request.type() + " without invalidating cells members already filled. "
                                + "Text and Long text can change into each other, or into List.");
            }
        }
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

    /** Column mutations only make sense on a SHEET — a worksheet's structure is its blocks. */
    private void requireSheet(ExerciseTemplate template) {
        if (template.getKind() != ExerciseTemplateKind.SHEET) {
            throw new BadRequestException("This exercise is a worksheet — it has blocks, not columns.");
        }
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
