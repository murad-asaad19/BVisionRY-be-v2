package com.bvisionry.notification.push;

import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface UserNotificationRepository extends JpaRepository<UserNotification, UUID> {

    List<UserNotification> findByUserIdOrderByCreatedAtDesc(UUID userId, Limit limit);

    long countByUserIdAndReadAtIsNull(UUID userId);

    /** Owner-scoped lookup so one user cannot mark another's notification read. */
    Optional<UserNotification> findByIdAndUserId(UUID id, UUID userId);

    @Modifying
    @Transactional
    @Query("UPDATE UserNotification n SET n.readAt = :now, n.updatedAt = :now "
            + "WHERE n.userId = :userId AND n.readAt IS NULL")
    int markAllRead(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Retention purge (see {@link NotificationRetentionJob}). */
    @Transactional
    long deleteByCreatedAtBefore(Instant cutoff);
}
