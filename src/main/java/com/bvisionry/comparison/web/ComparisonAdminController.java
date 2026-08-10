package com.bvisionry.comparison.web;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.comparison.dto.ComparisonSummaryDto;
import com.bvisionry.comparison.dto.FounderComparisonDto;
import com.bvisionry.comparison.dto.PillarMappingResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

/**
 * Org-admin comparison reads for one cohort. Same guard stack as the other
 * org-scoped consoles: {@code OrgAccessInterceptor} on the route pattern plus
 * the class {@code @PreAuthorize}; every service query carries {@code orgId}.
 * The pillar mapping is READ-ONLY here — it is managed by Bvisionry
 * (SUPER_ADMIN, {@link ComparisonPlatformController}).
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/cohorts/{cohortId}",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Distance comparison (admin)", description = "Cohort comparison reads + mapping view.")
public class ComparisonAdminController {

    private final ComparisonQueryService queries;
    private final ComparisonMappingService mappingService;
    private final com.bvisionry.comparison.repository.ComparisonReadRepository reads;

    @GetMapping("/comparisons")
    public List<ComparisonSummaryDto> list(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        return queries.cohortComparisons(orgId, cohortId);
    }

    @GetMapping("/comparisons/{userId}")
    public FounderComparisonDto detail(@PathVariable UUID orgId, @PathVariable UUID cohortId,
                                       @PathVariable UUID userId) {
        return queries.cohortComparisonForUser(orgId, cohortId, userId);
    }

    /** The cohort's designated pair mapping, read-only ("managed by Bvisionry"). */
    @GetMapping("/comparison-mapping")
    public PillarMappingResponse mapping(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        reads.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
        var pair = reads.designatedPair(cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Designated comparison pair for cohort", cohortId.toString()));
        return mappingService.mappingForPair(pair.baselinePipelineId(), pair.distancePipelineId());
    }
}
