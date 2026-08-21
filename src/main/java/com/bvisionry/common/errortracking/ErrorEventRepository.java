package com.bvisionry.common.errortracking;

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
public interface ErrorEventRepository extends JpaRepository<ErrorEvent, UUID> {

    /** Optional source filter; pass {@code null} to browse both tiers. Sorted by the Pageable. */
    @Query("SELECT e FROM ErrorEvent e WHERE (:source IS NULL OR e.source = :source)")
    Page<ErrorEvent> findFiltered(@Param("source") ErrorSource source, Pageable pageable);

    /**
     * Retention purge: delete up to {@code batchSize} rows older than the cutoff,
     * oldest first, returning how many actually went. Same shape (and reasoning) as
     * {@code AICallLogRepository.deleteBatchByCalledAtBefore} — JPQL {@code DELETE}
     * cannot express {@code LIMIT}, so this is native, and batching keeps each
     * transaction's lock footprint on the write-hot table bounded.
     */
    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM error_events
            WHERE id IN (SELECT id FROM error_events WHERE created_at < :cutoff ORDER BY created_at LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteBatchByCreatedAtBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
