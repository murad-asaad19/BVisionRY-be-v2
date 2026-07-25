package com.bvisionry.auth.jwt;

import org.springframework.stereotype.Component;

import com.bvisionry.auth.entity.User;

import jakarta.persistence.PostRemove;
import jakarta.persistence.PostUpdate;

/**
 * JPA entity listener that evicts a user's {@link UserPrincipalCache} entry
 * whenever their {@code users} row is updated or removed — the central,
 * fail-closed hook that keeps role/status/org-membership changes effective on
 * the next request without wiring an evict call into every mutation site.
 *
 * <p>Instantiated by Hibernate through Spring's {@code SpringBeanContainer},
 * so constructor injection works. Bulk JPQL/native updates bypass entity
 * listeners by design — the cache TTL bounds staleness for those.</p>
 */
@Component
public class UserPrincipalCacheEvictionListener {

    private final UserPrincipalCache cache;

    public UserPrincipalCacheEvictionListener(UserPrincipalCache cache) {
        this.cache = cache;
    }

    @PostUpdate
    @PostRemove
    void onUserChanged(User user) {
        cache.evict(user.getId());
    }
}
