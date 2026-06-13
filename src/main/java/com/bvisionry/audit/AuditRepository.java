package com.bvisionry.audit;

import com.bvisionry.audit.entity.AuditLog;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

public interface AuditRepository extends JpaRepository<AuditLog, UUID> {

    AuditLog findFirstByEntityTypeAndEntityIdAndActionTypeOrderByOccurredAtDesc(
            String entityType, UUID entityId, String actionType);

    List<AuditLog> findByEntityTypeAndEntityIdOrderByOccurredAtDesc(
            String entityType, UUID entityId, Pageable pageable);

    long countByActionTypeAndOccurredAtAfter(String actionType, Instant occurredAt);

    @Query("""
        SELECT a FROM AuditLog a
        WHERE a.organizationId = :orgId
          AND a.actionType <> 'USER_LOGIN'
        ORDER BY a.occurredAt DESC
        """)
    List<AuditLog> findOrgScopedActivity(@Param("orgId") UUID orgId, Pageable pageable);
}
