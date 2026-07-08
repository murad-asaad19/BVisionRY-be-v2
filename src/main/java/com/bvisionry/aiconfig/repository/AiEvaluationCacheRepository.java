package com.bvisionry.aiconfig.repository;

import com.bvisionry.aiconfig.entity.AiEvaluationCache;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface AiEvaluationCacheRepository extends JpaRepository<AiEvaluationCache, UUID> {

    Optional<AiEvaluationCache> findByCacheKey(String cacheKey);

    boolean existsByCacheKey(String cacheKey);

    /**
     * Retention purge: delete up to {@code batchSize} rows older than the cutoff in a single
     * statement, oldest first, and return how many were actually deleted. Mirrors
     * {@code AICallLogRepository.deleteBatchByCalledAtBefore} exactly — the inner
     * {@code SELECT ... ORDER BY created_at LIMIT} uses {@code idx_ai_evaluation_cache_created_at};
     * a JPQL {@code DELETE} cannot express {@code LIMIT}, hence the native query. The caller loops
     * this until a batch deletes fewer than {@code batchSize} rows, keeping each transaction bounded.
     */
    @Modifying
    @Transactional
    @Query(value = """
            DELETE FROM ai_evaluation_cache
            WHERE id IN (SELECT id FROM ai_evaluation_cache WHERE created_at < :cutoff ORDER BY created_at LIMIT :batchSize)
            """, nativeQuery = true)
    int deleteBatchByCreatedAtBefore(@Param("cutoff") Instant cutoff, @Param("batchSize") int batchSize);
}
