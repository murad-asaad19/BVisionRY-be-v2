package com.bvisionry.pipeline.controller;

import com.bvisionry.pipeline.dto.PillarCourseMappingResponse;
import com.bvisionry.pipeline.dto.PillarCourseMappingsRequest;
import com.bvisionry.pipeline.dto.PillarCreateRequest;
import com.bvisionry.pipeline.dto.PillarResponse;
import com.bvisionry.pipeline.dto.PillarUpdateRequest;
import com.bvisionry.pipeline.dto.ReorderRequest;
import com.bvisionry.pipeline.service.PillarCourseMappingService;
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
    private final PillarCourseMappingService courseMappingService;

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

    /**
     * The courses this pillar recommends at each of its score bands (roadmap §7
     * item 9). Config only — the enrolment engine that reads it ships separately.
     *
     * <p>SUPER_ADMIN: both ends of the relation are platform-wide content
     * (pillars are global and only SUPER_ADMIN may author them; the catalog is
     * not org-filtered), so there is no org grain to scope to. The data layer
     * scopes the pillar load by {@code (pillarId, pipelineId)}, so a pillar id
     * from another pipeline 404s rather than answering.
     *
     * <p>The method annotation is an identical RESTATEMENT of the class one, not
     * an additional check — Spring REPLACES the class expression with the
     * method's, it never ANDs them, so a method annotation is only ever as
     * strong as what it says. Restating the same expression is the one safe
     * redundancy: it keeps the gate if the class annotation is ever removed, and
     * it puts the answer next to the handler for anyone grepping.
     */
    @GetMapping("/{id}/course-mappings")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<List<PillarCourseMappingResponse>> listCourseMappings(
            @PathVariable UUID pipelineId,
            @PathVariable UUID id) {
        return ResponseEntity.ok(courseMappingService.list(pipelineId, id));
    }

    /** Replaces the pillar's whole rule set; an empty list clears it. */
    @PutMapping("/{id}/course-mappings")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<List<PillarCourseMappingResponse>> replaceCourseMappings(
            @PathVariable UUID pipelineId,
            @PathVariable UUID id,
            @Valid @RequestBody PillarCourseMappingsRequest request) {
        return ResponseEntity.ok(courseMappingService.replace(pipelineId, id, request));
    }
}
