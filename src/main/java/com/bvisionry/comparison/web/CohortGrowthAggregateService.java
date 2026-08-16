package com.bvisionry.comparison.web;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.programaccess.OrgCohortAccess;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.FounderComparisonPillar;
import com.bvisionry.comparison.domain.NarrativeKind;
import com.bvisionry.comparison.domain.NarrativeStatus;
import com.bvisionry.comparison.domain.PillarComparisonState;
import com.bvisionry.comparison.domain.ShiftNarrative;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto.PillarAggregateDto;
import com.bvisionry.comparison.repository.FounderComparisonPillarRepository;
import com.bvisionry.comparison.repository.FounderComparisonRepository;
import com.bvisionry.comparison.repository.ShiftNarrativeRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The cohort's per-pillar growth rollup: average before/after/shift per mapped
 * distance pillar plus narrative kind counts, computed in memory from the
 * stored comparison rows — cohorts are tens of members, and the same three
 * repository reads already back every other cohort surface.
 *
 * <p>Tenant-scoped like the rest of the slice: every read carries
 * {@code orgId}, so a platform cohort folds down to the calling org's members
 * only.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CohortGrowthAggregateService {

    private final FounderComparisonRepository comparisons;
    private final FounderComparisonPillarRepository pillarRows;
    private final ShiftNarrativeRepository narrativeRows;
    private final OrgCohortAccess orgCohorts;

    public CohortGrowthAggregateDto aggregate(UUID orgId, UUID cohortId) {
        orgCohorts.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));

        List<FounderComparison> rows = comparisons.findByOrgIdAndCohortId(orgId, cohortId);
        List<FounderComparisonPillar> pillars = rows.isEmpty() ? List.of()
                : pillarRows.findByComparisonIdIn(rows.stream()
                        .map(FounderComparison::getId).toList());

        Map<UUID, UUID> userByComparison = rows.stream()
                .collect(java.util.stream.Collectors.toMap(FounderComparison::getId,
                        FounderComparison::getUserId));

        // Only MAPPED rows have a before AND an after to average; one-sided
        // pillars (newly measured / not re-measured) stay out of the rollup,
        // same as they stay out of the charts.
        List<FounderComparisonPillar> mapped = pillars.stream()
                .filter(p -> p.getState() == PillarComparisonState.MAPPED)
                .filter(p -> p.getDistancePillarId() != null)
                .toList();

        // A narrative only counts while its (member, pillar) is still mapped —
        // the same still-attached rule the member-facing reads apply (§6).
        Set<String> attachedPairs = new HashSet<>();
        for (FounderComparisonPillar p : mapped) {
            attachedPairs.add(pairKey(userByComparison.get(p.getComparisonId()),
                    p.getDistancePillarId()));
        }

        Map<UUID, List<FounderComparisonPillar>> byPillar = new LinkedHashMap<>();
        for (FounderComparisonPillar p : mapped) {
            byPillar.computeIfAbsent(p.getDistancePillarId(), k -> new ArrayList<>()).add(p);
        }

        Map<UUID, List<ShiftNarrative>> narrativesByPillar = narrativeRows
                .findByOrgIdAndCohortIdAndStatus(orgId, cohortId, NarrativeStatus.APPROVED)
                .stream()
                .filter(n -> attachedPairs.contains(pairKey(n.getUserId(), n.getDistancePillarId())))
                .collect(java.util.stream.Collectors.groupingBy(ShiftNarrative::getDistancePillarId));

        List<PillarAggregateDto> pillarDtos = byPillar.entrySet().stream()
                .map(e -> toPillarDto(e.getKey(), e.getValue(),
                        narrativesByPillar.getOrDefault(e.getKey(), List.of())))
                // Ranking order (settled with the operator): biggest average
                // shift first — the sorted table IS the shift ranking.
                .sorted(Comparator
                        .comparing((PillarAggregateDto p) -> p.avgDelta() == null)
                        .thenComparing(PillarAggregateDto::avgDelta,
                                Comparator.nullsLast(Comparator.reverseOrder()))
                        .thenComparing(PillarAggregateDto::pillarName,
                                Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        return new CohortGrowthAggregateDto(rows.size(),
                mean(rows, FounderComparison::getOverallBefore),
                mean(rows, FounderComparison::getOverallAfter),
                mean(rows, FounderComparison::getOverallDelta),
                pillarDtos);
    }

    private static PillarAggregateDto toPillarDto(UUID distancePillarId,
                                                  List<FounderComparisonPillar> rows,
                                                  List<ShiftNarrative> narratives) {
        Map<NarrativeKind, Integer> kindCounts = new EnumMap<>(NarrativeKind.class);
        for (NarrativeKind kind : NarrativeKind.values()) {
            kindCounts.put(kind, 0);
        }
        // One narrative per (member, pillar) by construction, so counting each
        // narrative's distinct kinds once IS counting distinct members.
        for (ShiftNarrative n : narratives) {
            for (NarrativeKind kind : kindsOf(n)) {
                kindCounts.merge(kind, 1, Integer::sum);
            }
        }
        // Last snapshot wins — snapshots only diverge across a mid-cohort
        // rename, and any of them names the same pillar.
        String name = rows.get(rows.size() - 1).getPillarNameSnapshot();
        return new PillarAggregateDto(distancePillarId, name, rows.size(),
                mean(rows, FounderComparisonPillar::getBeforePct),
                mean(rows, FounderComparisonPillar::getAfterPct),
                mean(rows, FounderComparisonPillar::getDelta),
                narratives.size(), kindCounts);
    }

    /** Both stored shapes count: breakdown items, or the pre-V189 single kind. */
    private static Set<NarrativeKind> kindsOf(ShiftNarrative n) {
        Set<NarrativeKind> kinds = new HashSet<>();
        if (n.getItems() != null) {
            for (ShiftNarrative.Item item : n.getItems()) {
                if (item.kind() != null) {
                    kinds.add(item.kind());
                }
            }
        } else if (n.getKind() != null) {
            kinds.add(n.getKind());
        }
        return kinds;
    }

    /** Mean over the rows that HAVE the value, 1 decimal — null when none do. */
    private static <T> BigDecimal mean(List<T> rows, Function<T, BigDecimal> value) {
        List<BigDecimal> values = rows.stream().map(value).filter(Objects::nonNull).toList();
        if (values.isEmpty()) {
            return null;
        }
        BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        return sum.divide(BigDecimal.valueOf(values.size()), 1, RoundingMode.HALF_UP);
    }

    private static String pairKey(UUID userId, UUID distancePillarId) {
        return userId + ":" + distancePillarId;
    }
}
