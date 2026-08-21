package com.bvisionry.common.security;

import java.util.UUID;

/**
 * THE owner of "may this caller see tenant T?" — one predicate, asked through
 * every door: the {@code @orgAccess} SpEL bean, the org-path interceptor, and
 * imperative service-layer checks all delegate here, so the tenancy rule can
 * no longer fork per mechanism.
 *
 * <p>The rule (deliberately hierarchical, operator decision 2026-08-15):
 * SUPER_ADMIN sees every org; a user sees their own org; an ORG_ADMIN of a
 * parent organization also sees its direct sub-organizations
 * ({@link OrgHierarchyPort} — the hierarchy is one level deep).
 *
 * <p>Lives in {@code common} so feature slices can inject it without importing
 * {@code auth}; implemented in {@code config} (shared wiring layer).
 */
public interface OrgScope {

    /** May the authenticated caller see tenant {@code orgId}? Null → false. */
    boolean mayAccess(UUID orgId);

    /**
     * @throws org.springframework.security.access.AccessDeniedException unless
     *     {@link #mayAccess} — a uniform 403 so the response does not disclose
     *     whether the foreign org exists.
     */
    void require(UUID orgId);
}
