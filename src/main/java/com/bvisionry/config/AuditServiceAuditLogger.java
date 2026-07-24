package com.bvisionry.config;

import com.bvisionry.audit.AuditService;
import com.bvisionry.common.audit.AuditLogger;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.Map;
import java.util.UUID;

/**
 * {@link AuditLogger} adapter delegating to {@link AuditService}. Lives in
 * {@code config} (shared wiring layer, allowed to cross feature lines) so new
 * feature code can audit without a new feature→audit dependency edge —
 * same pattern as {@link SecurityContextCurrentUserAccessor}.
 */
@Component
@RequiredArgsConstructor
public class AuditServiceAuditLogger implements AuditLogger {

    private final AuditService auditService;

    @Override
    public void log(UUID actorId, UUID organizationId, String actionType,
                    String entityType, UUID entityId, Map<String, Object> details) {
        auditService.log(actorId, organizationId, actionType, entityType, entityId, details);
    }
}
