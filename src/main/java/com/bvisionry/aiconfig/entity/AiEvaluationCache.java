package com.bvisionry.aiconfig.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * A cached, already-parsed AI evaluation result keyed by a content hash of its inputs.
 * {@code cacheKey} is a SHA-256 over (model, temperature, system prompt, user message), so an
 * identical evaluation re-uses the stored {@code resultJson} instead of re-billing the provider.
 * {@code lastHitAt} is stamped on every hit; retention purges by {@code createdAt}.
 */
@Entity
@Table(name = "ai_evaluation_cache")
@Getter
@Setter
@NoArgsConstructor
public class AiEvaluationCache {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "cache_key", nullable = false, unique = true, length = 64)
    private String cacheKey;

    @Column(name = "call_type", nullable = false, length = 40)
    private String callType;

    @Column(nullable = false, length = 200)
    private String model;

    @Column(name = "result_json", nullable = false, columnDefinition = "TEXT")
    private String resultJson;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "last_hit_at")
    private Instant lastHitAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
