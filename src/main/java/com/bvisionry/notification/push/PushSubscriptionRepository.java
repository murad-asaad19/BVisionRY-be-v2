package com.bvisionry.notification.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface PushSubscriptionRepository extends JpaRepository<PushSubscription, UUID> {

    List<PushSubscription> findByUserIdIn(Collection<UUID> userIds);

    Optional<PushSubscription> findByEndpoint(String endpoint);

    /** Scoped to the owner so one user cannot unsubscribe another's browser. */
    @Transactional
    void deleteByEndpointAndUserId(String endpoint, UUID userId);

    /** Prune path for subscriptions the push service reports gone (404/410). */
    @Transactional
    void deleteByEndpoint(String endpoint);
}
