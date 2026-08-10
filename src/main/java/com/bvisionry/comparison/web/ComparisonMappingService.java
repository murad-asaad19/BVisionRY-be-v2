package com.bvisionry.comparison.web;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.comparison.domain.ComparisonPillarMapping;
import com.bvisionry.comparison.domain.MappingSource;
import com.bvisionry.comparison.dto.PillarMappingResponse;
import com.bvisionry.comparison.dto.PillarMappingResponse.MappingRow;
import com.bvisionry.comparison.dto.PillarMappingResponse.PillarOption;
import com.bvisionry.comparison.repository.ComparisonPillarMappingRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository;
import com.bvisionry.comparison.repository.ComparisonReadRepository.PillarRow;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * The GLOBAL pillar pair mapping per (baseline, distance) pipeline pair (spec
 * §5): auto-seeded by case-insensitive name match on first read; only
 * SUPER_ADMIN remaps/unmaps (the controllers gate that — org admins reach the
 * read path only). The invariant maintained by every write: each pillar of
 * either pipeline appears exactly once, on its own side, so one-sided rows
 * are the complete record of what is newly measured / not re-measured.
 */
@Service
@RequiredArgsConstructor
public class ComparisonMappingService {

    private final ComparisonPillarMappingRepository mappings;
    private final ComparisonReadRepository reads;

    /* ------------------------------------------------------------- reading */

    /** The pair's mapping, seeding it from name matches on first read. */
    @Transactional
    public PillarMappingResponse mappingForPair(UUID baselinePipelineId, UUID distancePipelineId) {
        List<PillarRow> base = reads.pillarsOf(baselinePipelineId);
        List<PillarRow> dist = reads.pillarsOf(distancePipelineId);
        // Set.copyOf, not Set.of: the same instrument may play BOTH roles
        // (a supported pair — spec D1), and Set.of rejects the duplicate.
        Map<UUID, String> pipelineNames =
                reads.pipelineNames(Set.copyOf(List.of(baselinePipelineId, distancePipelineId)));
        if (!pipelineNames.containsKey(baselinePipelineId)
                || !pipelineNames.containsKey(distancePipelineId)) {
            throw new ResourceNotFoundException("Pipeline", baselinePipelineId + " / " + distancePipelineId);
        }

        if (!mappings.existsByBaselinePipelineIdAndDistancePipelineId(
                baselinePipelineId, distancePipelineId)) {
            seed(baselinePipelineId, distancePipelineId, base, dist);
        } else {
            reconcileNewPillars(baselinePipelineId, distancePipelineId, base, dist);
        }

        Map<UUID, String> names = new HashMap<>();
        base.forEach(p -> names.put(p.id(), p.name()));
        dist.forEach(p -> names.put(p.id(), p.name()));

        List<MappingRow> rows = mappings
                .findByBaselinePipelineIdAndDistancePipelineId(baselinePipelineId, distancePipelineId)
                .stream()
                .map(m -> new MappingRow(m.getId(), m.getBaselinePillarId(),
                        names.get(m.getBaselinePillarId()), m.getDistancePillarId(),
                        names.get(m.getDistancePillarId()),
                        m.getSource().name(), m.getUpdatedAt()))
                .sorted(Comparator.comparing(r -> r.baselinePillarName() != null
                        ? r.baselinePillarName()
                        : r.distancePillarName(), Comparator.nullsLast(String::compareToIgnoreCase)))
                .toList();

        return new PillarMappingResponse(
                baselinePipelineId, pipelineNames.get(baselinePipelineId),
                distancePipelineId, pipelineNames.get(distancePipelineId),
                rows,
                base.stream().map(p -> new PillarOption(p.id(), p.name())).toList(),
                dist.stream().map(p -> new PillarOption(p.id(), p.name())).toList());
    }

    /* ------------------------------------------------------------- seeding */

    /**
     * Pure name-match: case-insensitive, trimmed. Returns baseline-pillar-id →
     * distance-pillar-id for every match; duplicate names on either side match
     * first-by-display-order (deterministic, admin can remap).
     */
    static Map<UUID, UUID> autoMatch(List<PillarRow> baseline, List<PillarRow> distance) {
        Map<String, UUID> distByName = new LinkedHashMap<>();
        for (PillarRow d : distance) {
            distByName.putIfAbsent(normalize(d.name()), d.id());
        }
        Map<UUID, UUID> matched = new LinkedHashMap<>();
        Set<UUID> used = new java.util.HashSet<>();
        for (PillarRow b : baseline) {
            UUID d = distByName.get(normalize(b.name()));
            if (d != null && used.add(d)) {
                matched.put(b.id(), d);
            }
        }
        return matched;
    }

    private static String normalize(String name) {
        return name == null ? "" : name.trim().toLowerCase(Locale.ROOT);
    }

    private void seed(UUID baselinePipelineId, UUID distancePipelineId,
                      List<PillarRow> base, List<PillarRow> dist) {
        Map<UUID, UUID> matched = autoMatch(base, dist);
        List<ComparisonPillarMapping> rows = new ArrayList<>();
        for (PillarRow b : base) {
            rows.add(row(baselinePipelineId, distancePipelineId, b.id(), matched.get(b.id())));
        }
        Set<UUID> mappedDistance = matched.values().stream().collect(Collectors.toSet());
        for (PillarRow d : dist) {
            if (!mappedDistance.contains(d.id())) {
                rows.add(row(baselinePipelineId, distancePipelineId, null, d.id()));
            }
        }
        mappings.saveAll(rows);
    }

    /**
     * Pillars added to either pipeline AFTER the seed get their own one-sided
     * row on read, so the mapping table never hides a pillar. Existing rows are
     * never touched here — that is the admin's call.
     */
    private void reconcileNewPillars(UUID baselinePipelineId, UUID distancePipelineId,
                                     List<PillarRow> base, List<PillarRow> dist) {
        List<ComparisonPillarMapping> existing = mappings
                .findByBaselinePipelineIdAndDistancePipelineId(baselinePipelineId, distancePipelineId);
        Set<UUID> knownBase = existing.stream().map(ComparisonPillarMapping::getBaselinePillarId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        Set<UUID> knownDist = existing.stream().map(ComparisonPillarMapping::getDistancePillarId)
                .filter(java.util.Objects::nonNull).collect(Collectors.toSet());
        List<ComparisonPillarMapping> fresh = new ArrayList<>();
        for (PillarRow b : base) {
            if (!knownBase.contains(b.id())) {
                fresh.add(row(baselinePipelineId, distancePipelineId, b.id(), null));
            }
        }
        for (PillarRow d : dist) {
            if (!knownDist.contains(d.id())) {
                fresh.add(row(baselinePipelineId, distancePipelineId, null, d.id()));
            }
        }
        if (!fresh.isEmpty()) {
            mappings.saveAll(fresh);
        }
    }

    private static ComparisonPillarMapping row(UUID baselinePipelineId, UUID distancePipelineId,
                                               UUID baselinePillarId, UUID distancePillarId) {
        ComparisonPillarMapping m = new ComparisonPillarMapping();
        m.setBaselinePipelineId(baselinePipelineId);
        m.setDistancePipelineId(distancePipelineId);
        m.setBaselinePillarId(baselinePillarId);
        m.setDistancePillarId(distancePillarId);
        m.setSource(MappingSource.AUTO);
        return m;
    }

    /* ------------------------------------------------------------- writing */

    /**
     * Link two pillars (SUPER_ADMIN). Reconciles whatever previously held
     * either side: the old partners fall back to one-sided rows, and the two
     * rows that named the pillars merge into one MANUAL row.
     */
    @Transactional
    public PillarMappingResponse map(UUID baselinePipelineId, UUID distancePipelineId,
                                     UUID baselinePillarId, UUID distancePillarId, UUID actorId) {
        // Seed first so mapping an untouched pair works.
        mappingForPair(baselinePipelineId, distancePipelineId);

        ComparisonPillarMapping baseRow = mappings
                .findByBaselinePipelineIdAndDistancePipelineIdAndBaselinePillarId(
                        baselinePipelineId, distancePipelineId, baselinePillarId)
                .orElseThrow(() -> new BadRequestException(
                        "Baseline pillar is not part of the baseline pipeline."));
        ComparisonPillarMapping distRow = mappings
                .findByBaselinePipelineIdAndDistancePipelineIdAndDistancePillarId(
                        baselinePipelineId, distancePipelineId, distancePillarId)
                .orElseThrow(() -> new BadRequestException(
                        "Distance pillar is not part of the distance pipeline."));

        if (!baseRow.getId().equals(distRow.getId())) {
            UUID oldPartner = baseRow.getDistancePillarId();
            // Step 1 — free both sides and FLUSH before any insert: Hibernate
            // orders inserts before updates, so inserting the fallback rows
            // first would collide with the partial unique indexes.
            distRow.setDistancePillarId(null);
            distRow.setSource(MappingSource.MANUAL);
            distRow.setUpdatedBy(actorId);
            if (distRow.getBaselinePillarId() == null) {
                mappings.delete(distRow);
            } else {
                mappings.save(distRow);
            }
            baseRow.setDistancePillarId(null);
            mappings.saveAndFlush(baseRow);
            // Step 2 — relink; the old distance partner becomes newly measured.
            baseRow.setDistancePillarId(distancePillarId);
            if (oldPartner != null) {
                ComparisonPillarMapping newlyMeasured =
                        row(baselinePipelineId, distancePipelineId, null, oldPartner);
                newlyMeasured.setSource(MappingSource.MANUAL);
                newlyMeasured.setUpdatedBy(actorId);
                mappings.save(newlyMeasured);
            }
        }
        baseRow.setSource(MappingSource.MANUAL);
        baseRow.setUpdatedBy(actorId);
        mappings.saveAndFlush(baseRow);

        return mappingForPair(baselinePipelineId, distancePipelineId);
    }

    /**
     * Split a mapped row into its two one-sided halves (SUPER_ADMIN) — the
     * baseline pillar becomes "not re-measured", the distance pillar "newly
     * measured".
     */
    @Transactional
    public PillarMappingResponse unmap(UUID mappingId, UUID actorId) {
        ComparisonPillarMapping m = mappings.findById(mappingId)
                .orElseThrow(() -> new ResourceNotFoundException("Mapping", mappingId.toString()));
        if (m.getBaselinePillarId() == null || m.getDistancePillarId() == null) {
            throw new BadRequestException("This row is not mapped.");
        }
        UUID distancePillarId = m.getDistancePillarId();
        m.setDistancePillarId(null);
        m.setSource(MappingSource.MANUAL);
        m.setUpdatedBy(actorId);
        // Flush the update BEFORE inserting the one-sided half — Hibernate
        // orders inserts first, which would trip the partial unique index.
        mappings.saveAndFlush(m);
        ComparisonPillarMapping newlyMeasured = row(m.getBaselinePipelineId(),
                m.getDistancePipelineId(), null, distancePillarId);
        newlyMeasured.setSource(MappingSource.MANUAL);
        newlyMeasured.setUpdatedBy(actorId);
        mappings.save(newlyMeasured);
        return mappingForPair(m.getBaselinePipelineId(), m.getDistancePipelineId());
    }
}
