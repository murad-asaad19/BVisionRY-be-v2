package com.bvisionry.comparison.web;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.orgmember.OrgMemberAccess;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.PillarComparisonState;
import com.bvisionry.comparison.dto.CohortComparisonStatus;
import com.bvisionry.comparison.dto.ComparisonSummaryDto;
import com.bvisionry.comparison.dto.FounderComparisonDto;
import com.bvisionry.comparison.dto.FounderComparisonDto.ComparisonPillarDto;
import com.bvisionry.comparison.dto.MyComparisonResponse;
import com.bvisionry.comparison.dto.MyComparisonResponse.TrajectoryPoint;
import com.bvisionry.comparison.repository.ComparisonReadRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository.PairCohortRow;
import com.bvisionry.comparison.repository.FounderComparisonPillarRepository;
import com.bvisionry.comparison.repository.FounderComparisonRepository;
import com.bvisionry.common.programaccess.OrgCohortAccess;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * Read-side assembly for the comparison surfaces. Tenancy is carried in every
 * query: member/coach reads are identity-scoped, org-admin reads always pass
 * {@code orgId} into the repository predicate.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ComparisonQueryService {

    private final FounderComparisonRepository comparisons;
    private final FounderComparisonPillarRepository pillars;
    private final ComparisonReadRepository reads;
    private final OrgMemberAccess members;
    private final OrgCohortAccess orgCohorts;
    private final ShiftNarrativeService narratives;
    private final ComparisonComputeService compute;

    /* ---------------------------------------------------------- member view */

    /**
     * The member's own growth comparison + report lifecycle state (spec §5):
     * {@code done} | {@code pending} | {@code none}. Pending renders ONLY when
     * a designated pair exists — a member with a single assessment and no
     * distance designation gets {@code none}, never a teased report.
     */
    public MyComparisonResponse myComparison(UUID userId) {
        Optional<PairCohortRow> pairCohort = reads.memberPairCohort(userId);
        // Anchored to a designated cohort → only that cohort's instruments
        // (operator rule 2026-08-14); no anchor → everything evaluated so far.
        // One central scoping, so the UI strip, the PDF table and the Excel
        // sheet — which all read this payload — can never disagree.
        List<TrajectoryPoint> trajectory = reads.trajectory(userId,
                        pairCohort.map(PairCohortRow::cohortId).orElse(null)).stream()
                .map(t -> new TrajectoryPoint(t.submissionId(), t.pipelineName(),
                        t.overallScore(), t.evaluatedAt()))
                .toList();

        if (pairCohort.isEmpty()) {
            return new MyComparisonResponse("none", null, null, null, trajectory);
        }
        PairCohortRow pair = pairCohort.get();
        return comparisons.findByCohortIdAndUserId(pair.cohortId(), userId)
                .map(c -> new MyComparisonResponse("done", pair.cohortId(), pair.cohortName(),
                        toDto(c, null), trajectory))
                .orElseGet(() -> {
                    // §5 guard, same-pipeline flavor: an equal pair can only
                    // ever compute through a DISTANCE milestone task's tag —
                    // without one the report is not coming, so never tease it.
                    if (pair.baselinePipelineId().equals(pair.distancePipelineId())
                            && reads.milestoneTask(pair.cohortId(), "DISTANCE").isEmpty()) {
                        return new MyComparisonResponse("none", null, null, null, trajectory);
                    }
                    return new MyComparisonResponse("pending", pair.cohortId(),
                            pair.cohortName(), null, trajectory);
                });
    }

    /* ------------------------------------------------------- org admin view */

    public List<ComparisonSummaryDto> cohortComparisons(UUID orgId, UUID cohortId) {
        requireCohortInOrg(orgId, cohortId);
        List<FounderComparison> rows = comparisons.findByOrgIdAndCohortId(orgId, cohortId);
        Map<UUID, String> names = reads.userNames(rows.stream()
                .map(FounderComparison::getUserId).collect(java.util.stream.Collectors.toSet()));
        return rows.stream()
                .map(c -> new ComparisonSummaryDto(c.getUserId(), names.get(c.getUserId()),
                        c.getOverallBefore(), c.getOverallAfter(), c.getOverallDelta(),
                        c.getOverallBandKey(), c.getOverallBandLabel(), c.getComputedAt()))
                .sorted(Comparator.comparing(ComparisonSummaryDto::userName,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();
    }

    /**
     * The cohort's uncomputed-but-ready founders (see
     * {@link ComparisonComputeService#foundersAwaitingComparison}) — the admin's
     * only visibility into a compute that never landed, since the failure path
     * is deliberately silent for the member's evaluation.
     */
    public CohortComparisonStatus cohortComparisonStatus(UUID orgId, UUID cohortId) {
        requireCohortInOrg(orgId, cohortId);
        // Org-scoped: a platform cohort (spec §13) spans orgs, so the pending list
        // must be filtered to THIS org — the platform variant below would leak
        // other tenants' founder ids and names.
        return buildStatus(compute.foundersAwaitingComparison(orgId, cohortId));
    }

    /** SUPER_ADMIN variant for the platform board (spec §13): no org in the URL. */
    public CohortComparisonStatus cohortComparisonStatusPlatform(UUID cohortId) {
        return buildStatus(compute.foundersAwaitingComparison(cohortId));
    }

    private CohortComparisonStatus buildStatus(List<UUID> pending) {
        Map<UUID, String> names = reads.userNames(pending);
        return new CohortComparisonStatus(pending.size(), pending.stream()
                .map(id -> new CohortComparisonStatus.PendingFounder(id, names.get(id)))
                .sorted(Comparator.comparing(CohortComparisonStatus.PendingFounder::name,
                        Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList());
    }

    /**
     * The per-cohort detail for one enrolled founder. Empty means "no computed
     * pair for this member yet" — an EXPECTED state (204 at the controller,
     * never a 404 for the browser console to shout about). Not-found stays for
     * the genuine cases: cohort outside the org, or a user outside the
     * cohort's org slice.
     */
    public Optional<FounderComparisonDto> cohortComparisonForUser(UUID orgId, UUID cohortId, UUID userId) {
        requireCohortInOrg(orgId, cohortId);
        if (!reads.cohortMembersInOrg(orgId, cohortId).contains(userId)) {
            throw new ResourceNotFoundException("Cohort member", userId.toString());
        }
        return comparisons.findByOrgIdAndCohortIdAndUserId(orgId, cohortId, userId)
                .map(c -> toDto(c, reads.userNames(Set.of(userId)).get(userId)));
    }

    /**
     * The org admin's door to the SAME lifecycle-aware payload the member and
     * coach get (state + trajectory + comparison) — the founder-profile Growth
     * tab needs the pending/none states and the trajectory, which the
     * per-cohort {@link #cohortComparisonForUser} detail deliberately lacks.
     * Extends the Phase A DTO rather than forking a parallel shape.
     */
    public MyComparisonResponse memberComparisonForAdmin(UUID orgId, UUID userId) {
        requireMemberInOrg(orgId, userId);
        return myComparison(userId);
    }

    /** The org-admin member gate, shared with the narrative review endpoints. */
    public void requireMemberInOrg(UUID orgId, UUID userId) {
        members.requireMemberOf(orgId, userId);
    }

    /* ----------------------------------------------------------- coach view */

    /**
     * The founder's growth read for an authorized coach — the CALLER must
     * already have passed the {@code CoachAccess} gate (controller concern).
     * Same shape as the member's own view.
     */
    public MyComparisonResponse founderComparisonForCoach(UUID founderId) {
        return myComparison(founderId);
    }

    /* -------------------------------------------------------------- helpers */

    private void requireCohortInOrg(UUID orgId, UUID cohortId) {
        orgCohorts.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
    }

    private FounderComparisonDto toDto(FounderComparison c, String userName) {
        // Set.copyOf: the two submissions are distinct by construction today,
        // but a duplicate here must never 500 the growth read.
        Map<UUID, Instant> evaluatedAt = reads.submissionEvaluatedAt(
                Set.copyOf(List.of(c.getBaselineSubmissionId(), c.getDistanceSubmissionId())));
        var pillarRows = pillars.findByComparisonId(c.getId());
        // The distance pillars this comparison still measures BEFORE AND AFTER —
        // the set that decides which narratives are still attached (§6). MAPPED
        // only: a remap can demote a pillar to newly-measured, and a narrative
        // about a shift is meaningless once there is no "before" to shift from.
        Set<UUID> mappedDistancePillarIds = pillarRows.stream()
                .filter(p -> p.getState() == PillarComparisonState.MAPPED)
                .map(com.bvisionry.comparison.domain.FounderComparisonPillar::getDistancePillarId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<ComparisonPillarDto> pillarDtos = pillarRows.stream()
                .map(p -> new ComparisonPillarDto(p.getBaselinePillarId(), p.getDistancePillarId(),
                        p.getPillarNameSnapshot(), p.getState().name(), p.getBeforePct(),
                        p.getAfterPct(), p.getDelta(), p.getBandKey(), p.getBandLabel(),
                        p.getMaturityBefore(), p.getMaturityAfter()))
                // Report order (spec §5): sorted by shift size, largest gain →
                // largest decline; one-sided rows after all mapped rows.
                .sorted(Comparator
                        .comparing((ComparisonPillarDto p) -> p.delta() == null)
                        .thenComparing(ComparisonPillarDto::delta,
                                Comparator.nullsLast(Comparator.reverseOrder())))
                .toList();
        return new FounderComparisonDto(c.getId(), c.getCohortId(), c.getUserId(), userName,
                c.getOverallBefore(), c.getOverallAfter(), c.getOverallDelta(),
                c.getOverallBandKey(), c.getOverallBandLabel(),
                evaluatedAt.get(c.getBaselineSubmissionId()),
                evaluatedAt.get(c.getDistanceSubmissionId()),
                c.getComputedAt(), pillarDtos,
                // Approved-only AND still-attached, on every surface that reads
                // this DTO (§6). The count of what is still in review rides
                // along so the member can be told "2 more are coming" — a
                // number, never a word of unapproved prose.
                narratives.approvedFor(c.getCohortId(), c.getUserId(), mappedDistancePillarIds),
                narratives.draftCountFor(c.getCohortId(), c.getUserId(), mappedDistancePillarIds));
    }
}
