package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.programflow.dto.AudienceDto;
import com.bvisionry.programflow.dto.BoardResponse;
import com.bvisionry.programflow.dto.ComposeRequest;
import com.bvisionry.programflow.dto.CreateModuleRequest;
import com.bvisionry.programflow.dto.ModuleDraft;
import com.bvisionry.programflow.dto.ModuleDto;
import com.bvisionry.programflow.dto.MoveTaskRequest;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.PulseResponse;
import com.bvisionry.programflow.dto.TaskDto;
import com.bvisionry.programflow.dto.UpdateAudienceRequest;
import com.bvisionry.programflow.dto.UpdateModuleRequest;
import com.bvisionry.programflow.dto.UpdateTaskRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Program-flow authoring for one platform cohort (spec §13 — super admin
 * only): program board, task builder, pulse, matrix, AI composer.
 *
 * <ul>
 *   <li>GET  …/cohorts/{cohortId}/program                        — full board</li>
 *   <li>PUT  …/cohorts/{cohortId}/program/settings               — tweakables</li>
 *   <li>POST …/cohorts/{cohortId}/program/modules                — create module</li>
 *   <li>PUT  …/cohorts/{cohortId}/program/modules/{moduleId}     — update module</li>
 *   <li>PUT  …/cohorts/{cohortId}/program/modules/{moduleId}/audience — assign</li>
 *   <li>POST …/cohorts/{cohortId}/program/modules/{moduleId}/tasks — create task</li>
 *   <li>PUT  …/cohorts/{cohortId}/program/tasks/{taskId}         — save task builder</li>
 * </ul>
 *
 * <p>Deliberately build-only: founder progress (matrix, pulse) is org data and
 * lives on the org participation surface ({@link OrgCohortController}).
 */
@RestController
@RequestMapping(path = "/api/cohorts/{cohortId}/program",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Program Flow (admin)", description = "Program board, task builder, cohort pulse.")
public class ProgramAdminController {

    private final ProgramAdminService service;
    private final ProgramAiService aiService;

    public ProgramAdminController(ProgramAdminService service, ProgramAiService aiService) {
        this.service = service;
        this.aiService = aiService;
    }

    @GetMapping
    public BoardResponse getBoard(@PathVariable UUID cohortId) {
        return service.getBoard(cohortId);
    }

    @PutMapping("/settings")
    public ProgramSettingsDto updateSettings(
            @PathVariable UUID cohortId,
            @Valid @RequestBody ProgramSettingsDto req) {
        return service.updateSettings(cohortId, req);
    }

    @PostMapping("/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public ModuleDto createModule(
            @PathVariable UUID cohortId,
            @Valid @RequestBody CreateModuleRequest req) {
        return service.createModule(cohortId, req);
    }

    @PutMapping("/modules/{moduleId}")
    public ModuleDto updateModule(
            @PathVariable UUID cohortId,
            @PathVariable UUID moduleId,
            @Valid @RequestBody UpdateModuleRequest req) {
        return service.updateModule(cohortId, moduleId, req);
    }

    @PutMapping("/modules/{moduleId}/audience")
    public AudienceDto updateAudience(
            @PathVariable UUID cohortId,
            @PathVariable UUID moduleId,
            @Valid @RequestBody UpdateAudienceRequest req) {
        return service.updateAudience(cohortId, moduleId, req);
    }

    @DeleteMapping("/modules/{moduleId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteModule(
            @PathVariable UUID cohortId,
            @PathVariable UUID moduleId) {
        service.deleteModule(cohortId, moduleId);
    }

    /** Creates a task; {@code taskType} defaults to LESSON (the form-based flow). */
    @PostMapping("/modules/{moduleId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto createTask(
            @PathVariable UUID cohortId,
            @PathVariable UUID moduleId,
            @org.springframework.web.bind.annotation.RequestParam(required = false)
            com.bvisionry.programflow.domain.ProgramTaskType taskType) {
        return service.createTask(cohortId, moduleId, taskType);
    }

    @PutMapping("/tasks/{taskId}")
    public TaskDto updateTask(
            @PathVariable UUID cohortId,
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateTaskRequest req) {
        return service.updateTask(cohortId, taskId, req);
    }

    @DeleteMapping("/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(
            @PathVariable UUID cohortId,
            @PathVariable UUID taskId) {
        service.deleteTask(cohortId, taskId);
    }

    /** Board drag-and-drop: move a task to a module/index (same module = reorder). */
    @PutMapping("/tasks/{taskId}/move")
    public TaskDto moveTask(
            @PathVariable UUID cohortId,
            @PathVariable UUID taskId,
            @Valid @RequestBody MoveTaskRequest req) {
        return service.moveTask(cohortId, taskId, req);
    }

    /** AI composer — SSE: {@code status}* then {@code draft} (ModuleDraft JSON) or {@code error}. */
    @PostMapping(value = "/ai/compose", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter compose(
            @PathVariable UUID cohortId,
            @Valid @RequestBody ComposeRequest req) {
        return aiService.compose(cohortId, req.prompt());
    }

    /** "Add to board" — persists a (possibly task-filtered) composer draft. */
    @PostMapping("/ai/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public ModuleDto addDraftModule(
            @PathVariable UUID cohortId,
            @Valid @RequestBody ModuleDraft draft) {
        return service.addDraftModule(cohortId, draft);
    }
}
