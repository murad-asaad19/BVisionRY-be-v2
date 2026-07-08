package com.bvisionry.evaluation;

import com.bvisionry.evaluation.entity.OverallSummary;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface OverallSummaryRepository extends JpaRepository<OverallSummary, UUID> {

    Optional<OverallSummary> findBySubmissionId(UUID submissionId);

    List<OverallSummary> findBySubmissionIdIn(List<UUID> submissionIds);

    /**
     * Platform-wide average score across all evaluated submissions.
     */
    @Query("SELECT AVG(os.overallScorePercentage) FROM OverallSummary os WHERE os.overallScorePercentage > 0")
    BigDecimal findPlatformAverageScore();

    /**
     * Overall summaries for a user across time (history).
     */
    @Query("""
            SELECT os FROM OverallSummary os
            JOIN FETCH os.submission s
            JOIN FETCH s.assignment a
            JOIN FETCH a.pipeline
            WHERE s.user.id = :userId
            AND s.status = 'EVALUATED'
            ORDER BY os.generatedAt DESC
            """)
    List<OverallSummary> findByUserIdOrderByGeneratedAtDesc(@Param("userId") UUID userId);
}
