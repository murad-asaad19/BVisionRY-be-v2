package com.bvisionry.evaluation;

import com.bvisionry.evaluation.entity.PillarEvaluationHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface PillarEvaluationHistoryRepository extends JpaRepository<PillarEvaluationHistory, UUID> {

    /**
     * Highest existing version_number for this (submission, pillar). The next
     * archived snapshot uses {@code findMaxVersion + 1}; returns 0 when no
     * snapshots exist yet so the first archive lands at version 1.
     */
    @Query("""
            SELECT COALESCE(MAX(h.versionNumber), 0)
            FROM PillarEvaluationHistory h
            WHERE h.submission.id = :submissionId AND h.pillar.id = :pillarId
            """)
    int findMaxVersion(@Param("submissionId") UUID submissionId,
                       @Param("pillarId") UUID pillarId);

    /**
     * Highest version number per pillar for the given submission, restricted to
     * the supplied pillar IDs. Collapses what would otherwise be one
     * {@link #findMaxVersion} query per pillar into a single GROUP BY round-trip.
     * Pillars with no prior snapshot are absent from the result — callers should
     * default missing entries to 0.
     */
    @Query("""
            SELECT h.pillar.id, MAX(h.versionNumber)
            FROM PillarEvaluationHistory h
            WHERE h.submission.id = :submissionId AND h.pillar.id IN :pillarIds
            GROUP BY h.pillar.id
            """)
    List<Object[]> findMaxVersionsByPillarIds(@Param("submissionId") UUID submissionId,
                                              @Param("pillarIds") Collection<UUID> pillarIds);
}
