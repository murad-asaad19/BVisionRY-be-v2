package com.bvisionry.common.audit;

import java.util.Map;
import java.util.UUID;

/**
 * Port for writing audit-log rows from code that must not depend on the
 * {@code audit} feature package. Mirrors
 * {@code com.bvisionry.audit.AuditService#log} exactly; the adapter lives in
 * {@code config} (shared wiring layer, allowed to cross feature lines).
 *
 * <p>Exists because the ArchUnit ratchet forbids NEW cross-feature dependency
 * edges: existing methods keep their frozen {@code AuditService} calls, but
 * newly added methods audit through this port — the same precedent as
 * {@link com.bvisionry.common.security.CurrentUserAccessor}.
 */
public interface AuditLogger {

    /**
     * @param actorId        acting user (nullable for system actions)
     * @param organizationId org whose activity feed shows the entry (nullable)
     * @param actionType     action constant, e.g. {@code SUB_ORG_CREATED}
     * @param entityType     entity type constant, e.g. {@code Organization}
     * @param entityId       id of the affected entity
     * @param details        structured context persisted with the row
     */
    void log(UUID actorId, UUID organizationId, String actionType,
             String entityType, UUID entityId, Map<String, Object> details);
}
