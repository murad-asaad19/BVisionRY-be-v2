package com.bvisionry.auth.jwt;

import java.time.Duration;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import com.bvisionry.auth.entity.User;

/**
 * Short-TTL, in-process cache of the authenticated {@link User} principal
 * (with its organization initialized), keyed by user id.
 *
 * <p>{@code JwtAuthenticationFilter} previously loaded user + organization
 * from the database on <em>every</em> authenticated request — the single most
 * frequent query in the system, paying a connection checkout on the hottest
 * path. This cache bounds that to at most one load per user per TTL window.</p>
 *
 * <p><strong>Staleness bounds.</strong> Any entity-level update to a
 * {@code users} row evicts its entry via {@link UserPrincipalCacheEvictionListener},
 * so role/status changes still take effect on the next request. The TTL is the
 * fallback ceiling for changes that bypass entity listeners (bulk JPQL updates,
 * organization suspension): at most {@code ttl} seconds of staleness.
 * {@code bvisionry.auth.principal-cache-ttl-seconds=0} disables caching
 * entirely (test profiles do this for determinism).</p>
 *
 * <p>Deliberately in-process (not Redis): the cached value is a JPA entity
 * graph, the TTL is seconds, and per-instance divergence is bounded by the
 * same TTL — serializing entities to a shared store buys nothing here.</p>
 */
@Component
public class UserPrincipalCache {

    // ponytail: blunt clear-on-overflow beats unbounded growth; swap for a real
    // LRU (Caffeine) if entry churn ever exceeds ~10k concurrently active users.
    private static final int MAX_ENTRIES = 10_000;

    private record Entry(User user, long expiresAtNanos) {}

    private final ConcurrentHashMap<UUID, Entry> cache = new ConcurrentHashMap<>();
    private final Duration ttl;

    @Autowired
    public UserPrincipalCache(
            @Value("${bvisionry.auth.principal-cache-ttl-seconds:10}") long ttlSeconds) {
        this(Duration.ofSeconds(ttlSeconds));
    }

    UserPrincipalCache(Duration ttl) {
        this.ttl = ttl;
    }

    Optional<User> get(UUID userId) {
        Entry entry = cache.get(userId);
        if (entry == null) {
            return Optional.empty();
        }
        if (System.nanoTime() - entry.expiresAtNanos() >= 0) {
            cache.remove(userId);
            return Optional.empty();
        }
        return Optional.of(entry.user());
    }

    void put(UUID userId, User user) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        if (cache.size() >= MAX_ENTRIES) {
            cache.clear();
        }
        cache.put(userId, new Entry(user, System.nanoTime() + ttl.toNanos()));
    }

    /** Drops one user's entry so the next request reloads it from the database. */
    public void evict(UUID userId) {
        cache.remove(userId);
    }
}
