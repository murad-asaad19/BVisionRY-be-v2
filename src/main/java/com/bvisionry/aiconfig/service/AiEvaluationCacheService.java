package com.bvisionry.aiconfig.service;

import com.bvisionry.aiconfig.entity.AiEvaluationCache;
import com.bvisionry.aiconfig.repository.AiEvaluationCacheRepository;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.Optional;

/**
 * Content-hash cache for AI evaluation results. Identical evaluation inputs re-use a stored,
 * already-parsed result instead of re-billing the provider (retakes and full re-runs with
 * unchanged answers are the common case). The cache is strictly best-effort: it owns its own
 * {@code enabled} flag and no-ops when disabled, and a write failure never affects the caller.
 *
 * <p>Only the primary pillar evaluation is cached (wired in {@code OpenRouterChatService}).
 * Escalation re-samples bypass it by construction — they exist to gather INDEPENDENT opinions.
 */
@Service
@Slf4j
public class AiEvaluationCacheService {

    private final AiEvaluationCacheRepository repository;
    private final boolean enabled;
    private final int retentionDays;
    private final int batchSize;
    private final int maxBatches;

    public AiEvaluationCacheService(
            AiEvaluationCacheRepository repository,
            @Value("${bvisionry.ai.eval-cache.enabled:true}") boolean enabled,
            @Value("${bvisionry.ai.eval-cache.retention-days:30}") int retentionDays,
            @Value("${bvisionry.ai.eval-cache.retention.batch-size:1000}") int batchSize,
            @Value("${bvisionry.ai.eval-cache.retention.max-batches:10000}") int maxBatches) {
        this.repository = repository;
        this.enabled = enabled;
        this.retentionDays = retentionDays;
        this.batchSize = batchSize;
        this.maxBatches = maxBatches;
    }

    /** Whether caching is on. Callers gate cache-key computation and hit/miss metrics on this. */
    public boolean isEnabled() {
        return enabled;
    }

    /**
     * Returns the stored result JSON for {@code cacheKey} on a hit (stamping {@code last_hit_at}
     * in the same transaction), or empty on a miss / when disabled.
     */
    @Transactional
    public Optional<String> lookup(String cacheKey) {
        if (!enabled) {
            return Optional.empty();
        }
        return repository.findByCacheKey(cacheKey).map(row -> {
            row.setLastHitAt(Instant.now());
            return row.getResultJson();
        });
    }

    /**
     * Stores a parsed result under {@code cacheKey}. Idempotent under races and never propagates:
     * caching is best-effort and must not fail or alter the evaluation it accompanies.
     */
    @Transactional
    public void store(String cacheKey, String callType, String model, String resultJson) {
        if (!enabled) {
            return;
        }
        // Pre-check avoids the constraint violation (and an aborted transaction) on the common
        // sequential path — a retake normally hits lookup() and never reaches store() at all.
        if (repository.existsByCacheKey(cacheKey)) {
            return;
        }
        try {
            AiEvaluationCache row = new AiEvaluationCache();
            row.setCacheKey(cacheKey);
            row.setCallType(callType);
            row.setModel(model);
            row.setResultJson(resultJson);
            repository.saveAndFlush(row);
        } catch (DataIntegrityViolationException e) {
            // Lost a genuine insert race on UNIQUE(cache_key): a concurrent identical evaluation
            // stored the same inputs microseconds earlier. Its row is equivalent, so ignore. The
            // final backstop against propagation lives at the call site (see OpenRouterChatService).
            log.debug("AI evaluation cache insert race for key {} ignored", cacheKey);
        }
    }

    /**
     * SHA-256 (lowercase hex) over the four cache-key parts joined with a single ' ' separator.
     * The separator prevents boundary collisions between adjacent parts (e.g. ("ab","c") vs
     * ("a","bc")). Deterministic: identical inputs always yield the same key.
     */
    public static String cacheKey(String model, double temperature, String systemPrompt, String userMessage) {
        String canonical = model + ' ' + temperature + ' ' + systemPrompt + ' ' + userMessage;
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(canonical.getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(hash);
        } catch (NoSuchAlgorithmException e) {
            // SHA-256 is a mandated JDK algorithm — its absence is unrecoverable, not a runtime case.
            throw new IllegalStateException("SHA-256 not available", e);
        }
    }

    /**
     * Runs daily (and ~5 min after boot). Bounds cache growth by purging rows older than the
     * retention window. Mirrors {@code AICallLogRetentionJob}: bounded batches each in their own
     * transaction (native LIMIT delete), looping until a batch clears fewer than {@code batch-size}
     * rows, capped at {@code max-batches}; a non-positive retention value disables the purge; fully
     * guarded so a failed purge never kills the scheduler. Serialised across replicas via ShedLock.
     */
    @Scheduled(fixedDelayString = "${bvisionry.ai.eval-cache.retention.interval-ms:86400000}",
            initialDelayString = "${bvisionry.ai.eval-cache.retention.initial-delay-ms:300000}")
    @SchedulerLock(name = "AiEvaluationCacheService_purgeOldEntries",
            lockAtMostFor = "PT1H", lockAtLeastFor = "PT1M")
    public void purgeOldEntries() {
        if (retentionDays <= 0) {
            return;
        }
        try {
            Instant cutoff = Instant.now().minus(Duration.ofDays(retentionDays));
            long totalDeleted = 0;
            int batches = 0;
            int deleted;
            do {
                deleted = repository.deleteBatchByCreatedAtBefore(cutoff, batchSize);
                totalDeleted += deleted;
                batches++;
            } while (deleted == batchSize && batches < maxBatches);

            if (batches >= maxBatches && deleted == batchSize) {
                log.warn("AiEvaluationCacheService: hit max-batches cap ({}) with rows still older "
                                + "than {} day(s) remaining; will continue on the next run",
                        maxBatches, retentionDays);
            }
            if (totalDeleted > 0) {
                log.info("AiEvaluationCacheService: purged {} ai_evaluation_cache row(s) older than "
                                + "{} day(s) across {} batch(es)",
                        totalDeleted, retentionDays, batches);
            }
        } catch (RuntimeException e) {
            log.error("AiEvaluationCacheService purge failed: {}", e.getMessage(), e);
        }
    }
}
