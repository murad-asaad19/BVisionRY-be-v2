package com.bvisionry.aicalllog.repository;

import com.bvisionry.aicalllog.entity.AICallLog;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.UUID;

@Repository
public interface AICallLogRepository extends JpaRepository<AICallLog, UUID> {

    /**
     * Retention purge (F41): delete audit rows older than the cutoff. Uses the
     * existing {@code idx_ai_call_logs_called_at} index. Returns rows deleted so
     * the job can log/loop in batches.
     */
    @Modifying
    @Transactional
    @Query("DELETE FROM AICallLog l WHERE l.calledAt < :cutoff")
    int deleteByCalledAtBefore(@Param("cutoff") Instant cutoff);

    /**
     * Flexible filter query — any combination of {@code pipelineId} and
     * {@code submissionId} may be null. Results come back sorted by
     * called_at DESC via the Pageable's sort.
     */
    @Query("""
            SELECT l FROM AICallLog l
            WHERE (:pipelineId IS NULL OR l.pipelineId = :pipelineId)
              AND (:submissionId IS NULL OR l.submissionId = :submissionId)
            """)
    Page<AICallLog> findFiltered(@Param("pipelineId") UUID pipelineId,
                                 @Param("submissionId") UUID submissionId,
                                 Pageable pageable);
}
