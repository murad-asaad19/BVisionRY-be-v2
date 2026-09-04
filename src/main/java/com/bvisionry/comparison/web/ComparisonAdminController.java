package com.bvisionry.comparison.web;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.comparison.dto.CohortComparisonStatus;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto;
import com.bvisionry.comparison.dto.CohortPillarNoteDto;
import com.bvisionry.comparison.dto.ComparisonSummaryDto;
import com.bvisionry.comparison.dto.FounderComparisonDto;
import com.bvisionry.comparison.dto.PillarMappingResponse;
import com.bvisionry.comparison.dto.RecomputeResponse;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.bvisionry.common.programaccess.OrgCohortAccess;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.PutMapping;
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
@Tag(name = "Distance comparison (admin)",
        description = "Cohort comparison reads, mapping view + self-service recompute.")
public class ComparisonAdminController {

    private final ComparisonQueryService queries;
    private final ComparisonMappingService mappingService;
    private final ComparisonComputeService computeService;
    private final CohortGrowthAggregateService aggregates;
    private final CurrentUserAccessor currentUser;
    private final com.bvisionry.comparison.repository.ComparisonReadRepository reads;
    private final OrgCohortAccess orgCohorts;

    @GetMapping("/comparisons")
    public List<ComparisonSummaryDto> list(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        return queries.cohortComparisons(orgId, cohortId);
    }

    /**
     * One enrolled founder's detail — 204 when the cohort has no computed pair
     * for them yet (an expected state, not an error); 404 stays for a cohort
     * outside the org or a user outside the cohort's org slice.
     */
    @GetMapping("/comparisons/{userId}")
    public ResponseEntity<FounderComparisonDto> detail(@PathVariable UUID orgId, @PathVariable UUID cohortId,
                                                       @PathVariable UUID userId) {
        return queries.cohortComparisonForUser(orgId, cohortId, userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    /**
     * The cohort's per-pillar growth rollup — average before/after/shift per
     * mapped pillar plus approved-narrative kind counts. One payload behind the
     * cohort pillar table, its charts and the growth report export.
     */
    @GetMapping("/growth-aggregate")
    public CohortGrowthAggregateDto growthAggregate(@PathVariable UUID orgId,
                                                    @PathVariable UUID cohortId) {
        return aggregates.aggregate(orgId, cohortId);
    }

    /**
     * The coach's note under one pillar of the Growth tab's category cards
     * (V214): set, replace, or clear with a blank body. Org-scoped like the
     * aggregate it rides on — a platform cohort's notes are per org slice.
     */
    @PutMapping(path = "/growth-aggregate/pillars/{pillarId}/note",
            consumes = MediaType.APPLICATION_JSON_VALUE)
    public CohortPillarNoteDto pillarNote(@PathVariable UUID orgId, @PathVariable UUID cohortId,
                                          @PathVariable UUID pillarId,
                                          @Valid @RequestBody CohortPillarNoteDto.Request request) {
        return aggregates.saveNote(orgId, cohortId, pillarId, request.note(),
                currentUser.require().userId());
    }

    /**
     * How many enrolled founders are ready for a comparison that was never
     * stored — derived, so a swallowed compute stops being invisible.
     */
    @GetMapping("/comparison-status")
    public CohortComparisonStatus status(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        return queries.cohortComparisonStatus(orgId, cohortId);
    }

    /**
     * The org admin's self-service repair for their OWN cohort — the same
     * idempotent, roster-bounded, audited action the platform console exposes
     * ({@link ComparisonPlatformController#recompute}). The org admin is the
     * person who notices, so they must not need a Bvisionry operator.
     */
    @PostMapping("/comparisons/recompute")
    public RecomputeResponse recompute(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        orgCohorts.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
        return computeService.recomputeCohortForOrg(orgId, cohortId, currentUser.require().userId());
    }

    /**
     * The cohort's designated pair mapping, read-only ("managed by Bvisionry").
     * 204 when the cohort has no designated pair — a legitimate state on every
     * undesignated cohort's settings tab, so it must not be a 404.
     */
    @GetMapping("/comparison-mapping")
    public ResponseEntity<PillarMappingResponse> mapping(@PathVariable UUID orgId, @PathVariable UUID cohortId) {
        orgCohorts.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
        return reads.designatedPair(cohortId)
                .map(pair -> ResponseEntity.ok(mappingService.mappingForPair(
                        cohortId, pair.baselinePipelineId(), pair.distancePipelineId())))
                .orElseGet(() -> ResponseEntity.noContent().build());
    }
}
