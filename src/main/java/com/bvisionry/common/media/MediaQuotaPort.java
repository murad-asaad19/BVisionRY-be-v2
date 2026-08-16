package com.bvisionry.common.media;

import java.util.UUID;

/**
 * Shared-kernel view of "this org's upload just landed — is the org still
 * within its object-storage quota", consumed by feature packages that persist
 * a freshly-uploaded {@code minio://} marker into a business record but must
 * not depend on the {@code media} package directly.
 *
 * <p>Exactly the seam {@link MediaUrlPort} is (see its javadoc): features may
 * depend on {@code common}, never the reverse, and {@code ArchitectureRulesTest}
 * rule 1 freezes every existing feature→feature edge, so a NEW one (here:
 * {@code organization} → {@code media}) fails the build.
 *
 * <p>Implemented by {@code OrgStorageQuotaService} in the {@code media}
 * package.
 */
public interface MediaQuotaPort {

    /**
     * Reconciliation point for a browser-direct (presigned) upload. The
     * org-scoped presign now signs the declared size in as Content-Length, so
     * the declaration is binding at PUT time; this remains the second line of
     * defence — it covers objects written before that bound existed, and any
     * usage drift from deletes or out-of-band writes. It is
     * called at the point the flow next touches the marker after that — when
     * the caller asks to PERSIST it into a business record — and re-checks the
     * org's total usage against ITS REAL, MinIO-recorded sizes (including the
     * object just uploaded).
     *
     * <p>If that pushes the org over quota, the offending object is deleted
     * (so one oversized attempt doesn't permanently lock the org out of every
     * future upload by inflating usage forever) and the write is refused.
     *
     * @param orgId  the org the marker must belong to (already validated by
     *               the caller against its own key-prefix guard)
     * @param marker the {@code minio://bucket/objectKey} just uploaded
     * @throws RuntimeException (house convention: a well-formed request that
     *         conflicts with current account state — see the media package's
     *         {@code OrgStorageQuotaService} for the exact exception/status)
     *         when persisting this marker would leave the org over quota.
     */
    void reconcileAfterUpload(UUID orgId, String marker);

    /**
     * Releases an object this org is no longer referencing — called when a
     * business record's marker is replaced or cleared, so iterating on a logo
     * does not monotonically consume the org's quota with no self-service way
     * to free it. Best-effort and it never throws: a failed delete leaves one
     * orphan object for a later pass, it must not fail the business write. The
     * implementation refuses (log-and-return) any marker outside
     * {@code org/<orgId>/}, since the marker is caller-supplied.
     */
    void releaseReplaced(UUID orgId, String marker);
}
