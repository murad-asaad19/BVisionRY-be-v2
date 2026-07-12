package com.bvisionry.workshops.web;

import java.util.List;
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

import com.bvisionry.workshops.dto.AssignmentsResponse;
import com.bvisionry.workshops.dto.BoardStyleRequest;
import com.bvisionry.workshops.dto.BuilderResponse;
import com.bvisionry.workshops.dto.CreateExerciseRequest;
import com.bvisionry.workshops.dto.CreateTaskRequest;
import com.bvisionry.workshops.dto.CreateWorkshopRequest;
import com.bvisionry.workshops.dto.MemberAnswersResponse;
import com.bvisionry.workshops.dto.ReorderRequest;
import com.bvisionry.workshops.dto.UpdateBuilderRequest;
import com.bvisionry.workshops.dto.UpdateTaskRequest;
import com.bvisionry.workshops.dto.UpdateWorkshopRequest;
import com.bvisionry.workshops.dto.WorkshopAnalyticsResponse;
import com.bvisionry.workshops.dto.WorkshopDto;
import com.bvisionry.workshops.dto.WorkshopLiveResponse;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;

/**
 * Admin workshop management for an org: workshop lifecycle, the exercise
 * pipeline builder, and analytics.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/workshops", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@Tag(name = "Workshops (admin)", description = "Workshop lifecycle, exercise builder, analytics.")
public class WorkshopAdminController {

    private final WorkshopAdminService service;

    public WorkshopAdminController(WorkshopAdminService service) {
        this.service = service;
    }

    // ------------------------------------------------------------ workshops

    @GetMapping
    public List<WorkshopDto> list(@PathVariable UUID orgId) {
        return service.list(orgId);
    }

    @PostMapping
    @ResponseStatus(HttpStatus.CREATED)
    public WorkshopDto create(@PathVariable UUID orgId, @Valid @RequestBody CreateWorkshopRequest req) {
        return service.create(orgId, req);
    }

    @PutMapping("/{workshopId}")
    public WorkshopDto update(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                              @Valid @RequestBody UpdateWorkshopRequest req) {
        return service.update(orgId, workshopId, req);
    }

    @DeleteMapping("/{workshopId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void delete(@PathVariable UUID orgId, @PathVariable UUID workshopId) {
        service.delete(orgId, workshopId);
    }

    @PostMapping("/{workshopId}/reset")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void reset(@PathVariable UUID orgId, @PathVariable UUID workshopId) {
        service.reset(orgId, workshopId);
    }

    // ------------------------------------------------------------ builder

    @GetMapping("/{workshopId}/builder")
    public BuilderResponse builder(@PathVariable UUID orgId, @PathVariable UUID workshopId) {
        return service.builder(orgId, workshopId);
    }

    @PostMapping("/{workshopId}/exercises")
    @ResponseStatus(HttpStatus.CREATED)
    public BuilderResponse.ExerciseDto createExercise(
            @PathVariable UUID orgId, @PathVariable UUID workshopId,
            @Valid @RequestBody CreateExerciseRequest req) {
        return service.createExercise(orgId, workshopId, req);
    }

    @PutMapping("/{workshopId}/exercises/{exerciseId}")
    public void renameExercise(
            @PathVariable UUID orgId, @PathVariable UUID workshopId, @PathVariable UUID exerciseId,
            @Valid @RequestBody CreateExerciseRequest req) {
        service.renameExercise(orgId, workshopId, exerciseId, req);
    }

    @DeleteMapping("/{workshopId}/exercises/{exerciseId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteExercise(
            @PathVariable UUID orgId, @PathVariable UUID workshopId, @PathVariable UUID exerciseId) {
        service.deleteExercise(orgId, workshopId, exerciseId);
    }

    @PutMapping("/{workshopId}/exercises/order")
    public void reorderExercises(
            @PathVariable UUID orgId, @PathVariable UUID workshopId,
            @Valid @RequestBody ReorderRequest req) {
        service.reorderExercises(orgId, workshopId, req);
    }

    // ------------------------------------------------------------ tasks

    @PostMapping("/{workshopId}/exercises/{exerciseId}/tasks")
    @ResponseStatus(HttpStatus.CREATED)
    public BuilderResponse.TaskDto createTask(
            @PathVariable UUID orgId, @PathVariable UUID workshopId, @PathVariable UUID exerciseId,
            @Valid @RequestBody CreateTaskRequest req) {
        return service.createTask(orgId, workshopId, exerciseId, req);
    }

    @PutMapping("/{workshopId}/exercises/{exerciseId}/tasks/{taskId}")
    public BuilderResponse.TaskDto updateTask(
            @PathVariable UUID orgId, @PathVariable UUID workshopId, @PathVariable UUID exerciseId,
            @PathVariable UUID taskId, @Valid @RequestBody UpdateTaskRequest req) {
        return service.updateTask(orgId, workshopId, exerciseId, taskId, req);
    }

    @DeleteMapping("/{workshopId}/exercises/{exerciseId}/tasks/{taskId}")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    public void deleteTask(
            @PathVariable UUID orgId, @PathVariable UUID workshopId, @PathVariable UUID exerciseId,
            @PathVariable UUID taskId) {
        service.deleteTask(orgId, workshopId, exerciseId, taskId);
    }

    /** Replace the whole workshop builder in one save (builder "Save changes"). */
    @PutMapping("/{workshopId}/builder")
    public BuilderResponse updateBuilder(
            @PathVariable UUID orgId, @PathVariable UUID workshopId,
            @Valid @RequestBody UpdateBuilderRequest req) {
        return service.updateBuilder(orgId, workshopId, req);
    }

    @PutMapping("/{workshopId}/exercises/{exerciseId}/tasks/order")
    public void reorderTasks(
            @PathVariable UUID orgId, @PathVariable UUID workshopId, @PathVariable UUID exerciseId,
            @Valid @RequestBody ReorderRequest req) {
        service.reorderTasks(orgId, workshopId, exerciseId, req);
    }

    // ------------------------------------------------------------ assignments

    /** Each team's pinned card hands per SORT task. */
    @GetMapping("/{workshopId}/assignments")
    public AssignmentsResponse assignments(@PathVariable UUID orgId, @PathVariable UUID workshopId) {
        return service.assignments(orgId, workshopId);
    }

    /** Deal the SORT task's hand to every team that doesn't have one yet. */
    @PostMapping("/{workshopId}/assignments/{taskId}/deal")
    public AssignmentsResponse dealCards(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                                         @PathVariable UUID taskId) {
        return service.dealCards(orgId, workshopId, taskId);
    }

    // ------------------------------------------------------------ analytics

    @GetMapping("/{workshopId}/analytics")
    public WorkshopAnalyticsResponse analytics(@PathVariable UUID orgId, @PathVariable UUID workshopId) {
        return service.analytics(orgId, workshopId);
    }

    /** One member's final-review answers — opened from the completion log. */
    @GetMapping("/{workshopId}/members/{userId}/answers")
    public MemberAnswersResponse memberAnswers(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                                               @PathVariable UUID userId) {
        return service.memberAnswers(orgId, workshopId, userId);
    }

    /** The live results board — polled by the admin analytics surface. */
    @GetMapping("/{workshopId}/live")
    public WorkshopLiveResponse live(@PathVariable UUID orgId, @PathVariable UUID workshopId) {
        return service.live(orgId, workshopId);
    }

    @PutMapping("/{workshopId}/board-style")
    public void setBoardStyle(@PathVariable UUID orgId, @PathVariable UUID workshopId,
                              @Valid @RequestBody BoardStyleRequest req) {
        service.setBoardStyle(orgId, workshopId, req.style());
    }
}
