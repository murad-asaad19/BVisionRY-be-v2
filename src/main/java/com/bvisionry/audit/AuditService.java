package com.bvisionry.audit;

import com.bvisionry.audit.entity.AuditLog;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class AuditService {

    private final AuditRepository auditRepository;

    /**
     * Records an audit row stamped with the organization the action belongs to.
     * Pass {@code null} for {@code organizationId} on platform-scoped events
     * (AI config, prompt templates, attention thresholds, pipeline catalogue).
     * Org-scoped queries — notably the per-org Activity feed — filter on
     * {@code organization_id} directly, so getting this right at write time is
     * what keeps the feed coherent across member moves and deletions.
     */
    public void log(UUID actorId, UUID organizationId, String actionType,
                    String entityType, UUID entityId, Map<String, Object> details) {
        AuditLog entry = new AuditLog();
        entry.setActorId(actorId);
        entry.setOrganizationId(organizationId);
        entry.setActionType(actionType);
        entry.setEntityType(entityType);
        entry.setEntityId(entityId);
        entry.setDetailsJson(details);
        auditRepository.save(entry);
    }
}
