package com.bvisionry.comparison.web;

import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
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
 * The coach's half of the shift-narrative review gate. Spec §6 says
 * "admin/coach reads/edits → approved" — a coach reviews and approves exactly
 * like an admin, gated by the shared {@link CoachAccess} assignment-union
 * predicate (a founder outside the union is a 404, as everywhere in the coach
 * console). Route floor {@code /api/v1/coach/**} requires COACH in
 * SecurityConfig; the class re-asserts it.
 */
@RestController
@RequestMapping(path = "/api/v1/coach/founders/{founderId}/shift-narratives",
        produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('COACH')")
@RequiredArgsConstructor
@Tag(name = "Shift narratives (coach)",
        description = "Draft/approve review gate for an assigned founder's AI shift narratives.")
public class ShiftNarrativeCoachController {

    private final ShiftNarrativeService narratives;
    private final MemberGrowthSummaryService summaries;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public NarrativeReviewResponse list(@PathVariable UUID founderId) {
        requireSeen(founderId);
        return narratives.review(founderId);
    }

    @PostMapping("/generate")
    public ShiftNarrativeDto generate(@PathVariable UUID founderId,
                                      @Valid @RequestBody GenerateNarrativeRequest request) {
        return narratives.generateForPillar(founderId, request.distancePillarId(),
                requireSeen(founderId).viewerId());
    }

    /** One click, every remaining eligible pillar — the staff "Generate narratives" button. */
    @PostMapping("/generate-all")
    public GenerateAllNarrativesResponse generateAll(@PathVariable UUID founderId) {
        return narratives.generateAllForFounder(founderId, requireSeen(founderId).viewerId());
    }

    @PatchMapping(path = "/{narrativeId}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ShiftNarrativeDto update(@PathVariable UUID founderId, @PathVariable UUID narrativeId,
                                    @Valid @RequestBody UpdateNarrativeRequest request) {
        return narratives.update(founderId, narrativeId, request, requireSeen(founderId).viewerId());
    }

    @PostMapping("/{narrativeId}/approve")
    public ShiftNarrativeDto approve(@PathVariable UUID founderId, @PathVariable UUID narrativeId) {
        return narratives.approve(founderId, narrativeId, requireSeen(founderId).viewerId());
    }

    @DeleteMapping("/{narrativeId}")
    public void delete(@PathVariable UUID founderId, @PathVariable UUID narrativeId) {
        narratives.delete(founderId, narrativeId, requireSeen(founderId).viewerId());
    }

    /* ------------------------------------------- the member growth summary */

    /** 204 when the founder has no summary yet — an expected state, not a 404. */
    @GetMapping("/summary")
    public ResponseEntity<MemberGrowthSummaryDto> summary(@PathVariable UUID founderId) {
        requireSeen(founderId);
        return summaries.forReview(founderId)
                .map(ResponseEntity::ok)
                .orElseGet(() -> ResponseEntity.noContent().build());
    }

    @PostMapping("/summary/generate")
    public MemberGrowthSummaryDto generateSummary(@PathVariable UUID founderId) {
        return summaries.generate(founderId, requireSeen(founderId).viewerId());
    }

    @PatchMapping(path = "/summary", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MemberGrowthSummaryDto updateSummary(@PathVariable UUID founderId,
                                                @Valid @RequestBody UpdateGrowthSummaryRequest request) {
        return summaries.update(founderId, request, requireSeen(founderId).viewerId());
    }

    @PostMapping("/summary/approve")
    public MemberGrowthSummaryDto approveSummary(@PathVariable UUID founderId) {
        return summaries.approve(founderId, requireSeen(founderId).viewerId());
    }

    @DeleteMapping("/summary")
    public void deleteSummary(@PathVariable UUID founderId) {
        summaries.delete(founderId, requireSeen(founderId).viewerId());
    }

    /** The coach must currently see this founder — a revoked assignment locks them out. */
    private CoachAccess.ViewedFounder requireSeen(UUID founderId) {
        return coachAccess.requireSees(founderId);
    }
}
