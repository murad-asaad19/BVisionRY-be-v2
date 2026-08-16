package com.bvisionry.programflow.web;

import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.programflow.dto.BoardResponse;
import com.bvisionry.programflow.dto.ComposeRequest;
import com.bvisionry.programflow.dto.ModuleDraft;
import com.bvisionry.programflow.dto.ProgramSettingsDto;
import com.bvisionry.programflow.dto.SaveBoardRequest;

import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Program-flow authoring for one platform cohort (spec §13 — super admin only).
 *
 * <ul>
 *   <li>GET  …/cohorts/{cohortId}/program          — full board (+ its version)</li>
 *   <li>PUT  …/cohorts/{cohortId}/program/board    — save the whole board</li>
 *   <li>PUT  …/cohorts/{cohortId}/program/settings — tweakables</li>
 *   <li>POST …/cohorts/{cohortId}/program/ai/compose — AI composer (SSE)</li>
 * </ul>
 *
 * <p><strong>One writer.</strong> The Curriculum builder holds every edit
 * locally and sends the whole board on Save (spec §13.10), so there is exactly
 * one endpoint that moves a module, task or field. The per-entity writes this
 * console started with (create module, update task, drag to move…) are gone with
 * their last caller: each was a way to change the board without the whole-board
 * validation — and without the version the save is conditional on.
 *
 * <p>Deliberately build-only: founder progress (matrix, pulse) is org data and
 * lives on the org participation surface ({@link OrgCohortController}).
 */
@RestController
@RequestMapping(path = "/api/cohorts/{cohortId}/program",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@Tag(name = "Program Flow (admin)", description = "Program board and cohort settings.")
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

    /**
     * The Curriculum builder's Save — the ONE write of an editing session: the
     * payload replaces the cohort's curriculum wholesale.
     *
     * <p>Answers 412 when the board moved under the admin ({@code
     * expectedVersion} is stale) and 409 when the save would delete member work;
     * repeat with {@code force} to go ahead with the latter.
     */
    @PutMapping("/board")
    public BoardResponse saveBoard(
            @PathVariable UUID cohortId,
            @Valid @RequestBody SaveBoardRequest req) {
        return service.saveBoard(cohortId, req);
    }

    @PutMapping("/settings")
    public ProgramSettingsDto updateSettings(
            @PathVariable UUID cohortId,
            @Valid @RequestBody ProgramSettingsDto req) {
        return service.updateSettings(cohortId, req);
    }

    /**
     * AI composer — SSE: {@code status}* then {@code draft} (ModuleDraft JSON)
     * or {@code error}. The draft is staged in the builder's local board and
     * reaches the server, like everything else, through Save.
     *
     * <p>The {@code @ApiResponse} is what puts {@link ModuleDraft} in the
     * exported schema: an SSE emitter is untyped, so without it the shape the
     * web app parses out of the stream would have no contract at all.
     */
    @PostMapping(value = "/ai/compose", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @ApiResponse(responseCode = "200", description = "status events, then one draft (or error)",
            content = @Content(mediaType = MediaType.TEXT_EVENT_STREAM_VALUE,
                    schema = @Schema(implementation = ModuleDraft.class)))
    public org.springframework.web.servlet.mvc.method.annotation.SseEmitter compose(
            @PathVariable UUID cohortId,
            @Valid @RequestBody ComposeRequest req) {
        return aiService.compose(cohortId, req.prompt());
    }
}
