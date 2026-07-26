package com.bvisionry.insights.service;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.insights.BenchmarkReadRepository;
import com.bvisionry.insights.BenchmarkReadRepository.PillarRef;
import com.bvisionry.insights.BenchmarkReadRepository.SegmentRow;
import com.bvisionry.insights.dto.BenchmarkResponse;
import com.bvisionry.insights.dto.BenchmarkSegmentDto;
import com.bvisionry.insights.dto.PillarBenchmarkDto;

import lombok.RequiredArgsConstructor;

/**
 * Assembles the per-pillar benchmark comparison. All scoping and min-sample
 * suppression happen inside {@link BenchmarkReadRepository}'s SQL; this class
 * only resolves the cohort guard (foreign or absent cohort → 404) and zips the
 * segment maps onto the pipeline's pillar axis.
 */
@Service
@RequiredArgsConstructor
public class BenchmarkService {

    private final BenchmarkReadRepository repository;

    /**
     * One read transaction across the segment queries — under autocommit the
     * cohort, org and platform distributions are independent snapshots, so a
     * submission landing mid-request can put a founder in one segment and not
     * another and move a sample count across the suppression floor.
     */
    @Transactional(readOnly = true)
    public BenchmarkResponse benchmarks(UUID orgId, UUID pipelineId, UUID cohortId) {
        // A DRAFT/ARCHIVED or absent pipeline reads as absent — the axis query
        // must not become an existence oracle for unpublished pillar names.
        if (!repository.pipelinePublished(pipelineId)) {
            throw new ResourceNotFoundException("Pipeline", pipelineId.toString());
        }
        String cohortName = null;
        if (cohortId != null) {
            cohortName = repository.cohortNameInOrg(orgId, cohortId)
                    .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
        }
        return assemble(pipelineId, cohortId, cohortName,
                repository.pillars(pipelineId),
                cohortId == null ? null : repository.cohortDistribution(orgId, cohortId, pipelineId),
                repository.orgDistribution(orgId, pipelineId),
                repository.platformDistribution(orgId, pipelineId));
    }

    /**
     * Pure merge, unit-tested on the sufficiency boundary. {@code cohort} is
     * null when no cohort was requested (→ null cohort segment on every row).
     */
    static BenchmarkResponse assemble(UUID pipelineId, UUID cohortId, String cohortName,
                                      List<PillarRef> pillars,
                                      Map<UUID, SegmentRow> cohort,
                                      Map<UUID, SegmentRow> org,
                                      Map<UUID, SegmentRow> platform) {
        List<PillarBenchmarkDto> rows = pillars.stream()
                .map(p -> new PillarBenchmarkDto(p.id(), p.name(),
                        cohort == null ? null : ownSegment(cohort.get(p.id())),
                        ownSegment(org.get(p.id())),
                        platformSegment(platform.get(p.id()))))
                .toList();
        return new BenchmarkResponse(BenchmarkReadRepository.MIN_SAMPLE,
                pipelineId, cohortId, cohortName, rows);
    }

    /**
     * Cohort/org segment — the caller's own tenant, so the sample count is
     * always named (it drives the "12 of 30 founders measured" unlock copy).
     * Scores are already NULL below the floor, gated in SQL.
     */
    private static BenchmarkSegmentDto ownSegment(SegmentRow row) {
        if (row == null) {
            return new BenchmarkSegmentDto(false, 0, null, null, null);
        }
        return new BenchmarkSegmentDto(row.sampleSize() >= BenchmarkReadRepository.MIN_SAMPLE,
                row.sampleSize(), row.mean(), row.p25(), row.p75());
    }

    /**
     * Platform segment — a pillar below either floor (total, or the caller's
     * complement) is absent from the query result (HAVING in SQL), so the
     * insufficient state carries no observed count at all: nothing about the
     * platform below the floors is disclosed.
     */
    private static BenchmarkSegmentDto platformSegment(SegmentRow row) {
        if (row == null) {
            return new BenchmarkSegmentDto(false, null, null, null, null);
        }
        return new BenchmarkSegmentDto(true, row.sampleSize(),
                row.mean(), row.p25(), row.p75());
    }
}
