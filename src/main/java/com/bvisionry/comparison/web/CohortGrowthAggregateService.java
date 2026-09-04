package com.bvisionry.comparison.web;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.programaccess.OrgCohortAccess;
import com.bvisionry.comparison.domain.CohortPillarNote;
import com.bvisionry.comparison.domain.FounderComparison;
import com.bvisionry.comparison.domain.FounderComparisonPillar;
import com.bvisionry.comparison.domain.NarrativeKind;
import com.bvisionry.comparison.domain.NarrativeStatus;
import com.bvisionry.comparison.domain.ShiftNarrative;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto.BandCountDto;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto.BandMoveDto;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto.PillarAggregateDto;
import com.bvisionry.comparison.dto.CohortGrowthAggregateDto.PillarContentDto;
import com.bvisionry.comparison.dto.CohortPillarNoteDto;
import com.bvisionry.comparison.repository.CohortPillarNoteRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository;
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
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * The cohort's per-pillar growth rollup: average before/after/shift per mapped
 * distance pillar, band distribution and moves, tagged programme content and
 * narrative kind counts, computed in memory from the stored comparison rows —
 * cohorts are tens of members, and the same repository reads already back
 * every other cohort surface.
 *
 * <p>Tenant-scoped like the rest of the slice: every read carries
 * {@code orgId}, so a platform cohort folds down to the calling org's members
 * only — and the coach note it carries is that org's own.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CohortGrowthAggregateService {

    private final FounderComparisonRepository comparisons;
    private final FounderComparisonPillarRepository pillarRows;
    private final ShiftNarrativeRepository narrativeRows;
    private final CohortPillarNoteRepository notes;
    private final ComparisonReadRepository reads;
    private final OrgCohortAccess orgCohorts;

    public CohortGrowthAggregateDto aggregate(UUID orgId, UUID cohortId) {
        orgCohorts.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));

        List<FounderComparison> rows = comparisons.findByOrgIdAndCohortId(orgId, cohortId);
        List<FounderComparisonPillar> mapped = mappedPillarRows(rows);

        Map<UUID, UUID> userByComparison = rows.stream()
                .collect(Collectors.toMap(FounderComparison::getId, FounderComparison::getUserId));

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

        // Nobody measured on both sides yet — the common state early in a
        // cohort. Nothing to band, tag or annotate, so the pillar-scoped reads
        // below (the tagged-content join above all) are skipped, not run and
        // thrown away.
        if (byPillar.isEmpty()) {
            return new CohortGrowthAggregateDto(rows.size(),
                    mean(rows, FounderComparison::getOverallBefore),
                    mean(rows, FounderComparison::getOverallAfter),
                    mean(rows, FounderComparison::getOverallDelta),
                    List.of());
        }

        Map<UUID, List<ShiftNarrative>> narrativesByPillar = narrativeRows
                .findByOrgIdAndCohortIdAndStatus(orgId, cohortId, NarrativeStatus.APPROVED)
                .stream()
                .filter(n -> attachedPairs.contains(pairKey(n.getUserId(), n.getDistancePillarId())))
                .collect(Collectors.groupingBy(ShiftNarrative::getDistancePillarId));

        // Three cohort-wide reads, never one per pillar: the band axis of each
        // pillar, every tagged LIVE task with the org slice's progress on it,
        // and the org's coach notes.
        Map<UUID, List<String>> bandOrder = reads.bandOrder(byPillar.keySet());
        Map<UUID, List<ComparisonReadRepository.PillarContentRow>> content =
                reads.pillarContent(orgId, cohortId).stream()
                        .collect(Collectors.groupingBy(ComparisonReadRepository.PillarContentRow::pillarId,
                                LinkedHashMap::new, Collectors.toList()));
        Map<UUID, CohortPillarNote> noteByPillar = notes.findByOrgIdAndCohortId(orgId, cohortId)
                .stream()
                .collect(Collectors.toMap(CohortPillarNote::getPillarId, Function.identity(), (a, b) -> a));

        List<PillarAggregateDto> pillarDtos = byPillar.entrySet().stream()
                .map(e -> toPillarDto(e.getKey(), e.getValue(),
                        narrativesByPillar.getOrDefault(e.getKey(), List.of()),
                        bandOrder.getOrDefault(e.getKey(), List.of()),
                        content.getOrDefault(e.getKey(), List.of()),
                        noteByPillar.get(e.getKey())))
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

    /**
     * Set, replace or clear (blank) the coach note on one of the cohort's
     * mapped distance pillars, for THIS org's slice. A pillar nobody in the org
     * has been measured on is a 404 rather than a note on nothing — the tab
     * only offers the affordance beside a measured pillar.
     */
    @Transactional
    public CohortPillarNoteDto saveNote(UUID orgId, UUID cohortId, UUID pillarId,
                                        String note, UUID userId) {
        orgCohorts.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
        if (!pillarRows.existsMappedInCohort(orgId, cohortId, pillarId)) {
            throw new ResourceNotFoundException("Pillar", pillarId.toString());
        }

        String text = note == null ? "" : note.strip();
        Optional<CohortPillarNote> existing = notes.findByOrgIdAndCohortIdAndPillarId(orgId, cohortId, pillarId);
        if (text.isEmpty()) {
            existing.ifPresent(notes::delete);
            return new CohortPillarNoteDto(pillarId, null, null);
        }
        CohortPillarNote row = existing.orElseGet(() -> {
            CohortPillarNote created = new CohortPillarNote();
            created.setOrgId(orgId);
            created.setCohortId(cohortId);
            created.setPillarId(pillarId);
            return created;
        });
        row.setNote(text);
        row.setUpdatedBy(userId);
        // Flush now: on an edit the entity is already managed and @PreUpdate
        // only stamps updatedAt at flush, so a plain save() would echo the
        // PREVIOUS edit's timestamp.
        row = notes.saveAndFlush(row);
        return new CohortPillarNoteDto(pillarId, row.getNote(), row.getUpdatedAt());
    }

    /**
     * Only MAPPED rows have a before AND an after to average; one-sided
     * pillars (newly measured / not re-measured) stay out of the rollup,
     * same as they stay out of the charts.
     */
    private List<FounderComparisonPillar> mappedPillarRows(List<FounderComparison> rows) {
        if (rows.isEmpty()) {
            return List.of();
        }
        return pillarRows.findByComparisonIdIn(rows.stream().map(FounderComparison::getId).toList())
                .stream()
                .filter(FounderComparisonPillar::isMapped)
                .toList();
    }

    private static PillarAggregateDto toPillarDto(UUID distancePillarId,
                                                  List<FounderComparisonPillar> rows,
                                                  List<ShiftNarrative> narratives,
                                                  List<String> bandOrder,
                                                  List<ComparisonReadRepository.PillarContentRow> content,
                                                  CohortPillarNote note) {
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
        List<FounderComparisonPillar> banded = rows.stream()
                .filter(r -> r.getMaturityBefore() != null && r.getMaturityAfter() != null)
                .toList();
        List<BandMoveDto> moves = bandMoves(banded, bandOrder);
        int movedUp = moves.stream()
                .filter(m -> !m.held() && !m.down())
                .mapToInt(BandMoveDto::members).sum();
        List<PillarContentDto> contentDtos = content.stream()
                .map(c -> new PillarContentDto(c.taskId(), c.name(), c.taskType(),
                        c.doneCount(), c.memberCount()))
                .toList();
        return new PillarAggregateDto(distancePillarId, name, rows.size(),
                mean(rows, FounderComparisonPillar::getBeforePct),
                mean(rows, FounderComparisonPillar::getAfterPct),
                mean(rows, FounderComparisonPillar::getDelta),
                narratives.size(), kindCounts, moves, movedUp,
                bandCounts(banded, bandOrder), contentDtos,
                note == null ? null : note.getNote(),
                note == null ? null : note.getUpdatedAt());
    }

    /**
     * Band transitions within ONE pillar, over the rows carrying BOTH
     * snapshots: a pillar measured on one side alone has no transition to
     * report, which is the same null-not-zero rule the averages follow.
     *
     * <p>Ordering is real moves first (biggest cohort first), then the held
     * buckets last — an admin reads this for "who moved", and the stayers are
     * the denominator, not the headline. Plural: grouping is by (from, to), so
     * a pillar where some founders held Strong and others held Formative emits
     * a held bucket for each, and a reader that renders only the first one
     * drops founders out of a total it still prints.
     */
    private static List<BandMoveDto> bandMoves(List<FounderComparisonPillar> banded,
                                               List<String> bandOrder) {
        Map<List<String>, List<FounderComparisonPillar>> buckets = banded.stream()
                .collect(Collectors.groupingBy(
                        r -> List.of(r.getMaturityBefore(), r.getMaturityAfter()),
                        LinkedHashMap::new, Collectors.toList()));
        return buckets.entrySet().stream()
                .map(e -> {
                    String from = e.getKey().get(0);
                    String to = e.getKey().get(1);
                    boolean held = from.equals(to);
                    return new BandMoveDto(from, to, e.getValue().size(), held,
                            !held && isDown(from, to, bandOrder, e.getValue()));
                })
                .sorted(Comparator
                        .comparing(BandMoveDto::held)
                        .thenComparing(Comparator.comparingInt(BandMoveDto::members).reversed())
                        .thenComparing(BandMoveDto::from, String::compareToIgnoreCase)
                        .thenComparing(BandMoveDto::to, String::compareToIgnoreCase))
                .toList();
    }

    /**
     * The pillar's own band order decides direction when both labels are on it.
     * Off-axis labels (a rename since the reading, "Unknown") fall back to the
     * members' shift: bands are monotonic in the score, so any row in one
     * (from, to) bucket moved the same way.
     */
    private static boolean isDown(String from, String to, List<String> bandOrder,
                                  List<FounderComparisonPillar> rows) {
        int fromAt = bandOrder.indexOf(from);
        int toAt = bandOrder.indexOf(to);
        if (fromAt >= 0 && toAt >= 0) {
            return toAt < fromAt;
        }
        return rows.stream()
                .map(FounderComparisonPillar::getDelta)
                .filter(Objects::nonNull)
                .anyMatch(d -> d.signum() < 0);
    }

    /**
     * Head-count per band on each side, in the pillar's band order with any
     * off-axis label appended in order of appearance — so the two bars draw
     * every banded member and each sums to the same population as the moves.
     */
    private static List<BandCountDto> bandCounts(List<FounderComparisonPillar> banded,
                                                 List<String> bandOrder) {
        if (banded.isEmpty()) {
            return List.of();
        }
        List<String> labels = new ArrayList<>(bandOrder);
        for (FounderComparisonPillar r : banded) {
            if (!labels.contains(r.getMaturityBefore())) {
                labels.add(r.getMaturityBefore());
            }
            if (!labels.contains(r.getMaturityAfter())) {
                labels.add(r.getMaturityAfter());
            }
        }
        return labels.stream()
                .map(label -> new BandCountDto(label,
                        (int) banded.stream().filter(r -> label.equals(r.getMaturityBefore())).count(),
                        (int) banded.stream().filter(r -> label.equals(r.getMaturityAfter())).count(),
                        bandOrder.contains(label)))
                .toList();
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
