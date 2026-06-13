package com.bvisionry.evaluation;

import com.bvisionry.evaluation.entity.OverallSummaryHistory;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.UUID;

public interface OverallSummaryHistoryRepository extends JpaRepository<OverallSummaryHistory, UUID> {

    /**
     * Highest existing version_number for this submission. The next archived
     * snapshot uses {@code findMaxVersion + 1}; returns 0 when no snapshots
     * exist yet.
     */
    @Query("""
            SELECT COALESCE(MAX(h.versionNumber), 0)
            FROM OverallSummaryHistory h
            WHERE h.submission.id = :submissionId
            """)
    int findMaxVersion(@Param("submissionId") UUID submissionId);
}
