package com.bvisionry.pipeline.controller;

import com.bvisionry.pipeline.dto.PillarCreateRequest;
import com.bvisionry.pipeline.dto.PillarResponse;
import com.bvisionry.pipeline.dto.PillarUpdateRequest;
import com.bvisionry.pipeline.dto.ReorderRequest;
import com.bvisionry.pipeline.service.PillarService;
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
@RequestMapping("/api/pipelines/{pipelineId}/pillars")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class PillarController {

    private final PillarService pillarService;

    @PostMapping
    public ResponseEntity<PillarResponse> create(
            @PathVariable UUID pipelineId,
            @Valid @RequestBody PillarCreateRequest request) {
        PillarResponse response = pillarService.create(pipelineId, request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<PillarResponse>> listByPipeline(@PathVariable UUID pipelineId) {
        return ResponseEntity.ok(pillarService.listByPipeline(pipelineId));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PillarResponse> getById(
            @PathVariable UUID pipelineId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(pillarService.getById(pipelineId, id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PillarResponse> update(
            @PathVariable UUID pipelineId,
            @PathVariable UUID id,
            @Valid @RequestBody PillarUpdateRequest request) {
        return ResponseEntity.ok(pillarService.update(pipelineId, id, request));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<PillarResponse> duplicate(
            @PathVariable UUID pipelineId,
            @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pillarService.duplicate(pipelineId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID pipelineId,
            @PathVariable UUID id) {
        pillarService.delete(pipelineId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/reorder")
    public ResponseEntity<Void> reorder(
            @PathVariable UUID pipelineId,
            @Valid @RequestBody ReorderRequest request) {
        pillarService.reorder(pipelineId, request);
        return ResponseEntity.noContent().build();
    }
}
