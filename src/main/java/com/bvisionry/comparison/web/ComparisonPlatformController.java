package com.bvisionry.comparison.web;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.comparison.dto.MapPillarsRequest;
import com.bvisionry.comparison.dto.PillarMappingResponse;
import com.bvisionry.comparison.dto.RecomputeResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * SUPER_ADMIN comparison controls (spec §5/§7): the global pillar pair
 * mapping (GET seeds by name match; map/unmap override it) and the explicit,
 * logged "Recompute cohort" action.
 */
@RestController
@RequestMapping(path = "/api/admin", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Distance comparison (platform)", description = "Pillar pair mapping + recompute.")
public class ComparisonPlatformController {

    private final ComparisonMappingService mappingService;
    private final ComparisonComputeService computeService;
    private final ComparisonQueryService queries;
    private final CurrentUserAccessor currentUser;

    /** The cohort's uncomputed-but-ready founders — the platform board's repair prompt (spec §13). */
    @GetMapping("/comparisons/cohorts/{cohortId}/status")
    public com.bvisionry.comparison.dto.CohortComparisonStatus status(@PathVariable UUID cohortId) {
        return queries.cohortComparisonStatusPlatform(cohortId);
    }

    @GetMapping("/comparison-mappings")
    public PillarMappingResponse mapping(@RequestParam UUID baselinePipelineId,
                                         @RequestParam UUID distancePipelineId) {
        return mappingService.mappingForPair(baselinePipelineId, distancePipelineId);
    }

    @PostMapping("/comparison-mappings/map")
    public PillarMappingResponse map(@Valid @RequestBody MapPillarsRequest req) {
        return mappingService.map(req.baselinePipelineId(), req.distancePipelineId(),
                req.baselinePillarId(), req.distancePillarId(), currentUser.require().userId());
    }

    @PostMapping("/comparison-mappings/{mappingId}/unmap")
    public PillarMappingResponse unmap(@PathVariable UUID mappingId) {
        return mappingService.unmap(mappingId, currentUser.require().userId());
    }

    /** Restate a cohort's history with current config — explicit and logged (spec §7). */
    @PostMapping("/comparisons/cohorts/{cohortId}/recompute")
    public RecomputeResponse recompute(@PathVariable UUID cohortId) {
        return computeService.recomputeCohort(cohortId, currentUser.require().userId());
    }
}
