package com.bvisionry.notification.push;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.transaction.annotation.Transactional;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface NotificationOptOutRepository extends JpaRepository<NotificationOptOut, UUID> {

    List<NotificationOptOut> findByUserId(UUID userId);

    List<NotificationOptOut> findByTypeAndUserIdIn(NotificationType type, Collection<UUID> userIds);

    boolean existsByUserIdAndType(UUID userId, NotificationType type);

    @Transactional
    void deleteByUserIdAndType(UUID userId, NotificationType type);
}
