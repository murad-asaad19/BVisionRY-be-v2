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
     * Retention purge (F41): delete up to {@code batchSize} audit rows older than the
     * cutoff in a single statement, oldest first, and return how many were actually
     * deleted. The inner {@code SELECT ... ORDER BY called_at LIMIT} uses the existing
     * {@code idx_ai_call_logs_called_at} index; a JPQL {@code DELETE} cannot express
     * {@code LIMIT}, hence the native query. The caller ({@link
     * com.bvisionry.aicalllog.service.AICallLogRetentionJob}) loops this until a batch
     * deletes fewer than {@code batchSize} rows, keeping each transaction (and the lock
     * footprint on the hot-path table) bounded.
     */
    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM ai_call_logs
            WHERE id IN (SELECT id FROM ai_call_logs WHERE called_at < :cutoff ORDER BY called_at LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteBatchByCalledAtBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);

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
