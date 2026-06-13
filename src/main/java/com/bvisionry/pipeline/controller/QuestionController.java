package com.bvisionry.pipeline.controller;

import com.bvisionry.pipeline.dto.QuestionCreateRequest;
import com.bvisionry.pipeline.dto.QuestionResponse;
import com.bvisionry.pipeline.dto.QuestionUpdateRequest;
import com.bvisionry.pipeline.dto.ReorderRequest;
import com.bvisionry.pipeline.service.QuestionService;
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
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/pipelines/{pipelineId}/pillars/{pillarId}/questions")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class QuestionController {

    private final QuestionService questionService;

    @PostMapping
    public ResponseEntity<QuestionResponse> create(
            @PathVariable UUID pipelineId,
            @PathVariable UUID pillarId,
            @Valid @RequestBody QuestionCreateRequest request) {
        QuestionResponse response = questionService.create(pipelineId, pillarId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<QuestionResponse>> listByPillar(
            @PathVariable UUID pipelineId,
            @PathVariable UUID pillarId) {
        return ResponseEntity.ok(questionService.listByPillar(pipelineId, pillarId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<QuestionResponse> getById(
            @PathVariable UUID pipelineId,
            @PathVariable UUID pillarId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(questionService.getById(pipelineId, pillarId, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<QuestionResponse> update(
            @PathVariable UUID pipelineId,
            @PathVariable UUID pillarId,
            @PathVariable UUID id,
            @Valid @RequestBody QuestionUpdateRequest request) {
        return ResponseEntity.ok(questionService.update(pipelineId, pillarId, id, request));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<QuestionResponse> duplicate(
            @PathVariable UUID pipelineId,
            @PathVariable UUID pillarId,
            @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.duplicate(pipelineId, pillarId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID pipelineId,
            @PathVariable UUID pillarId,
            @PathVariable UUID id) {
        questionService.delete(pipelineId, pillarId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/reorder")
    public ResponseEntity<Void> reorder(
            @PathVariable UUID pipelineId,
            @PathVariable UUID pillarId,
            @Valid @RequestBody ReorderRequest request) {
        questionService.reorder(pipelineId, pillarId, request);
        return ResponseEntity.noContent().build();
    }
}
