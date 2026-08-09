package com.bvisionry.comparison.web;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.dto.ComparisonSummaryDto;
import com.bvisionry.comparison.dto.FounderComparisonDto;
import com.bvisionry.comparison.dto.FounderComparisonDto.ComparisonPillarDto;
import com.bvisionry.comparison.dto.MyComparisonResponse;
import com.bvisionry.comparison.dto.MyComparisonResponse.TrajectoryPoint;
import com.bvisionry.comparison.repository.ComparisonReadRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository.PairCohortRow;
import com.bvisionry.comparison.repository.FounderComparisonPillarRepository;
import com.bvisionry.comparison.repository.FounderComparisonRepository;
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

    /* ---------------------------------------------------------- member view */

    /**
     * The member's own growth comparison + report lifecycle state (spec §5):
     * {@code done} | {@code pending} | {@code none}. Pending renders ONLY when
     * a designated pair exists — a member with a single assessment and no
     * distance designation gets {@code none}, never a teased report.
     */
    public MyComparisonResponse myComparison(UUID userId) {
        List<TrajectoryPoint> trajectory = reads.trajectory(userId).stream()
                .map(t -> new TrajectoryPoint(t.submissionId(), t.pipelineName(),
                        t.overallScore(), t.evaluatedAt()))
                .toList();

        Optional<PairCohortRow> pairCohort = reads.memberPairCohort(userId);
        if (pairCohort.isEmpty()) {
            return new MyComparisonResponse("none", null, null, null, trajectory);
        }
        PairCohortRow pair = pairCohort.get();
        return comparisons.findByCohortIdAndUserId(pair.cohortId(), userId)
                .map(c -> new MyComparisonResponse("done", pair.cohortId(), pair.cohortName(),
                        toDto(c, null), trajectory))
                .orElseGet(() -> new MyComparisonResponse("pending", pair.cohortId(),
                        pair.cohortName(), null, trajectory));
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

    public FounderComparisonDto cohortComparisonForUser(UUID orgId, UUID cohortId, UUID userId) {
        requireCohortInOrg(orgId, cohortId);
        FounderComparison c = comparisons.findByOrgIdAndCohortIdAndUserId(orgId, cohortId, userId)
                .orElseThrow(() -> new ResourceNotFoundException("Comparison for user", userId.toString()));
        String name = reads.userNames(Set.of(userId)).get(userId);
        return toDto(c, name);
    }

    /**
     * The org admin's door to the SAME lifecycle-aware payload the member and
     * coach get (state + trajectory + comparison) — the founder-profile Growth
     * tab needs the pending/none states and the trajectory, which the
     * per-cohort {@link #cohortComparisonForUser} detail deliberately lacks.
     * Extends the Phase A DTO rather than forking a parallel shape.
     */
    public MyComparisonResponse memberComparisonForAdmin(UUID orgId, UUID userId) {
        if (!reads.userInOrg(orgId, userId)) {
            throw new ResourceNotFoundException("Member", userId.toString());
        }
        return myComparison(userId);
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
        reads.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
    }

    private FounderComparisonDto toDto(FounderComparison c, String userName) {
        Map<UUID, Instant> evaluatedAt = reads.submissionEvaluatedAt(
                Set.of(c.getBaselineSubmissionId(), c.getDistanceSubmissionId()));
        List<ComparisonPillarDto> pillarDtos = pillars.findByComparisonId(c.getId()).stream()
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
                c.getComputedAt(), pillarDtos);
    }
}
