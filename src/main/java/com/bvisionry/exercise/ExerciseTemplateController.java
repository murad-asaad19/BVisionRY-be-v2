package com.bvisionry.exercise;

import com.bvisionry.exercise.dto.ExerciseColumnResponse;
import com.bvisionry.exercise.dto.ExerciseTemplateDetailResponse;
import com.bvisionry.exercise.dto.ExerciseTemplateResponse;
import com.bvisionry.exercise.dto.ReorderColumnsRequest;
import com.bvisionry.exercise.dto.UpdateTemplateStatusRequest;
import com.bvisionry.exercise.dto.UpsertExerciseColumnRequest;
import com.bvisionry.exercise.dto.UpsertExerciseTemplateRequest;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/** Super-admin authoring console for exercise templates. */
@RestController
@RequestMapping("/api/admin/exercise-templates")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class ExerciseTemplateController {

    private final ExerciseTemplateService templateService;

    @GetMapping
    public ResponseEntity<List<ExerciseTemplateResponse>> list(
            @RequestParam(required = false) ExerciseTemplateStatus status) {
        return ResponseEntity.ok(templateService.list(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<ExerciseTemplateDetailResponse> get(@PathVariable UUID id) {
        return ResponseEntity.ok(templateService.get(id));
    }

    @PostMapping
    public ResponseEntity<ExerciseTemplateDetailResponse> create(
            @Valid @RequestBody UpsertExerciseTemplateRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.create(request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<ExerciseTemplateDetailResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody UpsertExerciseTemplateRequest request) {
        return ResponseEntity.ok(templateService.update(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        templateService.delete(id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<ExerciseTemplateDetailResponse> updateStatus(
            @PathVariable UUID id,
            @Valid @RequestBody UpdateTemplateStatusRequest request) {
        return ResponseEntity.ok(templateService.updateStatus(id, request.status()));
    }

    @PostMapping("/{id}/columns")
    public ResponseEntity<ExerciseColumnResponse> addColumn(
            @PathVariable UUID id,
            @Valid @RequestBody UpsertExerciseColumnRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(templateService.addColumn(id, request));
    }

    @PutMapping("/{id}/columns/{columnId}")
    public ResponseEntity<ExerciseColumnResponse> updateColumn(
            @PathVariable UUID id,
            @PathVariable UUID columnId,
            @Valid @RequestBody UpsertExerciseColumnRequest request) {
        return ResponseEntity.ok(templateService.updateColumn(id, columnId, request));
    }

    @DeleteMapping("/{id}/columns/{columnId}")
    public ResponseEntity<Void> deleteColumn(
            @PathVariable UUID id,
            @PathVariable UUID columnId) {
        templateService.deleteColumn(id, columnId);
        return ResponseEntity.noContent().build();
    }

    @PutMapping("/{id}/columns/reorder")
    public ResponseEntity<List<ExerciseColumnResponse>> reorderColumns(
            @PathVariable UUID id,
            @Valid @RequestBody ReorderColumnsRequest request) {
        return ResponseEntity.ok(templateService.reorderColumns(id, request));
    }
}
