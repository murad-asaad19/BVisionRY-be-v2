package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
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
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.PulseResponse;
import com.bvisionry.programflow.dto.TaskDto;
import com.bvisionry.programflow.dto.UpdateAudienceRequest;
import com.bvisionry.programflow.dto.UpdateModuleRequest;
import com.bvisionry.programflow.dto.UpdateTaskRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Admin program-flow endpoints (program board, task builder, cohort pulse).
 *
 * <ul>
 *   <li>GET  /api/organizations/{orgId}/program                        — full board</li>
 *   <li>PUT  /api/organizations/{orgId}/program/settings               — tweakables</li>
 *   <li>POST /api/organizations/{orgId}/program/modules                — create module</li>
 *   <li>PUT  /api/organizations/{orgId}/program/modules/{moduleId}     — update module</li>
 *   <li>PUT  /api/organizations/{orgId}/program/modules/{moduleId}/audience — assign</li>
 *   <li>POST /api/organizations/{orgId}/program/modules/{moduleId}/tasks — create task</li>
 *   <li>PUT  /api/organizations/{orgId}/program/tasks/{taskId}         — save task builder</li>
 *   <li>GET  /api/organizations/{orgId}/program/pulse                  — cohort matrix</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/program", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@Tag(name = "Program Flow (admin)", description = "Program board, task builder, cohort pulse.")
public class ProgramAdminController {

    private final ProgramAdminService service;
    private final ProgramAiService aiService;

    public ProgramAdminController(ProgramAdminService service, ProgramAiService aiService) {
        this.service = service;
        this.aiService = aiService;
    }

    @GetMapping
    public BoardResponse getBoard(@PathVariable UUID orgId) {
        return service.getBoard(orgId);
    }

    @PutMapping("/settings")
    public ProgramSettingsDto updateSettings(
            @PathVariable UUID orgId,
            @Valid @RequestBody ProgramSettingsDto req) {
        return service.updateSettings(orgId, req);
    }

    @PostMapping("/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public ModuleDto createModule(
            @PathVariable UUID orgId,
            @Valid @RequestBody CreateModuleRequest req) {
        return service.createModule(orgId, req);
    }

    @PutMapping("/modules/{moduleId}")
    public ModuleDto updateModule(
            @PathVariable UUID orgId,
            @PathVariable UUID moduleId,
            @Valid @RequestBody UpdateModuleRequest req) {
        return service.updateModule(orgId, moduleId, req);
    }

    @PutMapping("/modules/{moduleId}/audience")
    public AudienceDto updateAudience(
            @PathVariable UUID orgId,
            @PathVariable UUID moduleId,
            @Valid @RequestBody UpdateAudienceRequest req) {
        return service.updateAudience(orgId, moduleId, req);
    }

    @PostMapping("/modules/{moduleId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public TaskDto createTask(
            @PathVariable UUID orgId,
            @PathVariable UUID moduleId) {
        return service.createTask(orgId, moduleId);
    }

    @PutMapping("/tasks/{taskId}")
    public TaskDto updateTask(
            @PathVariable UUID orgId,
            @PathVariable UUID taskId,
            @Valid @RequestBody UpdateTaskRequest req) {
        return service.updateTask(orgId, taskId, req);
    }

    @GetMapping("/pulse")
    public PulseResponse getPulse(@PathVariable UUID orgId) {
        return service.getPulse(orgId);
    }

    /** AI composer — SSE: {@code status}* then {@code draft} (ModuleDraft JSON) or {@code error}. */
    @PostMapping(value = "/ai/compose", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter compose(
            @PathVariable UUID orgId,
            @Valid @RequestBody ComposeRequest req) {
        return aiService.compose(orgId, req.prompt());
    }

    /** "Add to board" — persists a (possibly task-filtered) composer draft. */
    @PostMapping("/ai/modules")
    @ResponseStatus(HttpStatus.CREATED)
    public ModuleDto addDraftModule(
            @PathVariable UUID orgId,
            @Valid @RequestBody ModuleDraft draft) {
        return service.addDraftModule(orgId, draft);
    }
}
