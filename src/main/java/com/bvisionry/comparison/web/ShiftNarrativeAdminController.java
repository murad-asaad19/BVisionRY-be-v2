package com.bvisionry.comparison.web;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.comparison.dto.GenerateAllNarrativesResponse;
import com.bvisionry.comparison.dto.MemberGrowthSummaryDto;
import com.bvisionry.comparison.dto.NarrativeRequests.GenerateNarrativeRequest;
import com.bvisionry.comparison.dto.NarrativeRequests.UpdateGrowthSummaryRequest;
import com.bvisionry.comparison.dto.NarrativeRequests.UpdateNarrativeRequest;
import com.bvisionry.comparison.dto.NarrativeReviewResponse;
import com.bvisionry.comparison.dto.ShiftNarrativeDto;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * The org admin's half of the shift-narrative review gate (spec §6), hanging off
 * the founder profile. Same guard stack as the member-anchored comparison read:
 * SUPER_ADMIN, or an ORG_ADMIN of this org, plus {@code OrgAccessInterceptor} on
 * the {@code /api/organizations/{orgId}/**} floor. Org membership of the member
 * is re-asserted per call.
 *
 * <p>Drafts are staff-only by construction — the member's own payload
 * ({@code FounderComparisonDto.narratives}) is filtered to APPROVED in the
 * service, not here.
 */
@RestController
@RequestMapping(path = "/api/organizations/{orgId}/members/{userId}/shift-narratives",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN') or (hasAuthority('ORG_ADMIN') and @orgAccess.isInOrg(#orgId))")
@RequiredArgsConstructor
@Tag(name = "Shift narratives (admin)",
        description = "Draft/approve review gate for a founder's AI shift narratives.")
public class ShiftNarrativeAdminController {

    private final ShiftNarrativeService narratives;
    private final MemberGrowthSummaryService summaries;
    private final ComparisonQueryService queries;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public NarrativeReviewResponse list(@PathVariable UUID orgId, @PathVariable UUID userId) {
        queries.requireMemberInOrg(orgId, userId);
        return narratives.review(userId);
    }

    @PostMapping("/generate")
    public ShiftNarrativeDto generate(@PathVariable UUID orgId, @PathVariable UUID userId,
                                      @Valid @RequestBody GenerateNarrativeRequest request) {
        queries.requireMemberInOrg(orgId, userId);
        return narratives.generateForPillar(userId, request.distancePillarId(),
                currentUser.require().userId());
    }

    /** One click, every remaining eligible pillar — the staff "Generate narratives" button. */
    @PostMapping("/generate-all")
    public GenerateAllNarrativesResponse generateAll(@PathVariable UUID orgId,
                                                     @PathVariable UUID userId) {
        queries.requireMemberInOrg(orgId, userId);
        return narratives.generateAllForFounder(userId, currentUser.require().userId());
    }

    @PatchMapping(path = "/{narrativeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ShiftNarrativeDto update(@PathVariable UUID orgId, @PathVariable UUID userId,
                                    @PathVariable UUID narrativeId,
                                    @Valid @RequestBody UpdateNarrativeRequest request) {
        queries.requireMemberInOrg(orgId, userId);
        return narratives.update(userId, narrativeId, request, currentUser.require().userId());
    }

    @PostMapping("/{narrativeId}/approve")
    public ShiftNarrativeDto approve(@PathVariable UUID orgId, @PathVariable UUID userId,
                                     @PathVariable UUID narrativeId) {
        queries.requireMemberInOrg(orgId, userId);
        return narratives.approve(userId, narrativeId, currentUser.require().userId());
    }

    @DeleteMapping("/{narrativeId}")
    public void delete(@PathVariable UUID orgId, @PathVariable UUID userId,
                       @PathVariable UUID narrativeId) {
        queries.requireMemberInOrg(orgId, userId);
        narratives.delete(userId, narrativeId, currentUser.require().userId());
    }

    /* ------------------------------------------- the member growth summary */

    /** 204 when the member has no summary yet — an expected state, not a 404. */
    @GetMapping("/summary")
    public ResponseEntity<MemberGrowthSummaryDto> summary(@PathVariable UUID orgId,
                                                          @PathVariable UUID userId) {
        queries.requireMemberInOrg(orgId, userId);
        return summaries.forReview(userId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/summary/generate")
    public MemberGrowthSummaryDto generateSummary(@PathVariable UUID orgId, @PathVariable UUID userId) {
        queries.requireMemberInOrg(orgId, userId);
        return summaries.generate(userId, currentUser.require().userId());
    }

    @PatchMapping(path = "/summary", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MemberGrowthSummaryDto updateSummary(@PathVariable UUID orgId, @PathVariable UUID userId,
                                                @Valid @RequestBody UpdateGrowthSummaryRequest request) {
        queries.requireMemberInOrg(orgId, userId);
        return summaries.update(userId, request, currentUser.require().userId());
    }

    @PostMapping("/summary/approve")
    public MemberGrowthSummaryDto approveSummary(@PathVariable UUID orgId, @PathVariable UUID userId) {
        queries.requireMemberInOrg(orgId, userId);
        return summaries.approve(userId, currentUser.require().userId());
    }

    @DeleteMapping("/summary")
    public void deleteSummary(@PathVariable UUID orgId, @PathVariable UUID userId) {
        queries.requireMemberInOrg(orgId, userId);
        summaries.delete(userId, currentUser.require().userId());
    }
}
