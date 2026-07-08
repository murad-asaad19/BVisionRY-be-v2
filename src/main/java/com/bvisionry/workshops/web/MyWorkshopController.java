package com.bvisionry.workshops.web;

import java.util.List;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.workshops.dto.MyWorkshopDto;
import com.bvisionry.workshops.dto.PlayResponse;
import com.bvisionry.workshops.dto.RespondRequest;
import com.bvisionry.workshops.dto.SortResultDto;
import com.bvisionry.workshops.dto.SubmitSortRequest;
import com.bvisionry.workshops.dto.SubmitWeightsRequest;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Learner-facing workshop play.
 *
 * <ul>
 *   <li>GET  /api/my/workshops                                — my workshops</li>
 *   <li>GET  /api/my/workshops/{workshopId}                   — play state</li>
 *   <li>POST /api/my/workshops/{workshopId}/tasks/{id}/start  — start task (timer origin)</li>
 *   <li>POST /api/my/workshops/{workshopId}/tasks/{id}/sort   — grade a sort attempt</li>
 *   <li>POST /api/my/workshops/{workshopId}/tasks/{id}/weights— save scores / re-score</li>
 *   <li>POST /api/my/workshops/{workshopId}/tasks/{id}/complete — finish top task (may share)</li>
 *   <li>POST /api/my/workshops/{workshopId}/tasks/{id}/respond — answer / edit a question</li>
 *   <li>POST /api/my/workshops/{workshopId}/tasks/{id}/survey-complete — finish a survey task</li>
 *   <li>POST /api/my/workshops/{workshopId}/help                — ping the admin: team needs help</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/my/workshops", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("isAuthenticated()")
@Tag(name = "Workshops (learner)", description = "Team exercise play: envelopes, tasks, sharing.")
public class MyWorkshopController {

    private final MyWorkshopService service;

    public MyWorkshopController(MyWorkshopService service) {
        this.service = service;
    }

    @GetMapping
    public List<MyWorkshopDto> myWorkshops() {
        return service.myWorkshops();
    }

    @GetMapping("/{workshopId}")
    public PlayResponse play(@PathVariable UUID workshopId) {
        return service.play(workshopId);
    }

    @PostMapping("/{workshopId}/tasks/{taskId}/start")
    public PlayResponse start(@PathVariable UUID workshopId, @PathVariable UUID taskId) {
        return service.start(workshopId, taskId);
    }

    @PostMapping("/{workshopId}/tasks/{taskId}/sort")
    public SortResultDto sort(@PathVariable UUID workshopId, @PathVariable UUID taskId,
                              @Valid @RequestBody SubmitSortRequest req) {
        return service.submitSort(workshopId, taskId, req);
    }

    @PostMapping("/{workshopId}/tasks/{taskId}/weights")
    public PlayResponse weights(@PathVariable UUID workshopId, @PathVariable UUID taskId,
                                @Valid @RequestBody SubmitWeightsRequest req) {
        return service.submitWeights(workshopId, taskId, req);
    }

    @PostMapping("/{workshopId}/tasks/{taskId}/complete")
    public PlayResponse complete(@PathVariable UUID workshopId, @PathVariable UUID taskId) {
        return service.completeTop(workshopId, taskId);
    }

    /** Finish a SURVEY task — requires the member's survey response to exist. */
    @PostMapping("/{workshopId}/tasks/{taskId}/survey-complete")
    public PlayResponse completeSurvey(@PathVariable UUID workshopId, @PathVariable UUID taskId) {
        return service.completeSurvey(workshopId, taskId);
    }

    @PostMapping("/{workshopId}/help")
    public PlayResponse requestHelp(@PathVariable UUID workshopId) {
        return service.requestHelp(workshopId);
    }

    @PostMapping("/{workshopId}/tasks/{taskId}/respond")
    public PlayResponse respond(@PathVariable UUID workshopId, @PathVariable UUID taskId,
                                @Valid @RequestBody RespondRequest req) {
        return service.respond(workshopId, taskId, req);
    }
}
