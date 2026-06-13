package com.bvisionry.pipeline.controller;

import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.pipeline.dto.PipelineCreateRequest;
import com.bvisionry.pipeline.dto.PipelinePostCompletionRequest;
import com.bvisionry.pipeline.dto.PipelinePreviewResponse;
import com.bvisionry.pipeline.dto.PipelineResponse;
import com.bvisionry.pipeline.dto.PipelineStatusRequest;
import com.bvisionry.pipeline.dto.PipelineSummaryResponse;
import com.bvisionry.pipeline.dto.PipelineMetadataUpdateRequest;
import com.bvisionry.pipeline.dto.PipelineUpdateRequest;
import com.bvisionry.pipeline.dto.SimulateRequest;
import com.bvisionry.pipeline.service.PipelineService;
import com.bvisionry.pipeline.service.PipelineSimulationService;
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

@RestController
@RequestMapping("/api/pipelines")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class PipelineController {

    private final PipelineService pipelineService;
    private final PipelineSimulationService simulationService;

    @PostMapping
    public ResponseEntity<PipelineResponse> create(@Valid @RequestBody PipelineCreateRequest request) {
        PipelineResponse response = pipelineService.create(request);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @GetMapping
    public ResponseEntity<List<PipelineSummaryResponse>> listAll(
            @RequestParam(required = false) PipelineStatus status) {
        return ResponseEntity.ok(pipelineService.listAll(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<PipelineResponse> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(pipelineService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<PipelineResponse> update(
            @PathVariable UUID id,
            @Valid @RequestBody PipelineUpdateRequest request) {
        return ResponseEntity.ok(pipelineService.update(id, request));
    }

    @PatchMapping("/{id}/metadata")
    public ResponseEntity<PipelineResponse> updateMetadata(
            @PathVariable UUID id,
            @Valid @RequestBody PipelineMetadataUpdateRequest request) {
        return ResponseEntity.ok(pipelineService.updateMetadata(id, request));
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<PipelineResponse> transitionStatus(
            @PathVariable UUID id,
            @Valid @RequestBody PipelineStatusRequest request) {
        return ResponseEntity.ok(pipelineService.transitionStatus(id, request));
    }

    @PostMapping("/{id}/version")
    public ResponseEntity<PipelineResponse> createNewVersion(@PathVariable UUID id) {
        PipelineResponse response = pipelineService.createNewVersion(id);
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<PipelineResponse> duplicate(@PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED).body(pipelineService.duplicate(id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        pipelineService.deletePipeline(id);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/published")
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<List<PipelineSummaryResponse>> getPublishedCatalog() {
        return ResponseEntity.ok(pipelineService.getPublishedCatalog());
    }

    @GetMapping("/{id}/preview")
    public ResponseEntity<PipelinePreviewResponse> getPreview(@PathVariable UUID id) {
        return ResponseEntity.ok(pipelineService.getPreview(id));
    }

    /**
     * Simulate a full assessment evaluation without persisting.
     * Admin provides sample answers + tier (FREE/PREMIUM) and gets back the full results
     * as if a real member had submitted and been evaluated.
     */
    @PostMapping("/{id}/simulate")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<PipelineSimulationService.SimulationResult> simulate(
            @PathVariable UUID id,
            @Valid @RequestBody SimulateRequest request) {
        return ResponseEntity.ok(simulationService.simulate(id, request));
    }

    @PutMapping("/{id}/post-completion")
    public ResponseEntity<PipelineResponse> setPostCompletion(
            @PathVariable UUID id,
            @Valid @RequestBody PipelinePostCompletionRequest request) {
        return ResponseEntity.ok(pipelineService.setPostCompletion(id, request));
    }
}
