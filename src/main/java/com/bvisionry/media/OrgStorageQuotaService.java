package com.bvisionry.media;

import java.util.Locale;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;

import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.media.MediaQuotaPort;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;

/**
 * Per-org object-storage quota (roadmap §11: uploads were capped at 10MB/file
 * and UNBOUNDED IN COUNT — every white-label logo presign mints a fresh
 * object key under {@code org/<orgId>/branding/}, V154's IDOR guard
 * constrains WHERE it can land, never how many land, and nothing ever deletes
 * a replaced logo). OPERATOR RULING: 2 GiB default ceiling
 * ({@link MediaProperties#getOrgDefaultQuotaBytes()}), configurable, with a
 * per-org override ({@code organizations.storage_quota_bytes}, V160).
 *
 * <p><strong>Usage = live sum of the org's real object sizes in MinIO</strong>,
 * not a counter column. Nothing in this codebase records upload sizes
 * server-side today (there is no media table — see {@code MediaService}'s
 * javadoc: uploads are just {@code minio://} markers persisted by feature
 * endpoints), and a counter would drift the moment an object is added or
 * removed outside a path that remembers to adjust it. The object store
 * already tracks the real size of everything it holds, so
 * {@link #totalUsageBytes(UUID)} reads that directly by listing
 * {@code org/<orgId>/} — no separate bookkeeping to keep in sync.
 *
 * <p><strong>Two enforcement points, because the upload flow offers exactly
 * two moments to check:</strong>
 * <ol>
 *   <li>{@link #requireCapacity(UUID, long)} — at INITIATION (multipart
 *       upload, or presign before the URL is issued). The size is either the
 *       real bytes Spring already received (multipart) or a CLIENT-DECLARED
 *       value (presign — a presigned PUT never binds Content-Length, so this
 *       is the earliest and only pre-upload signal available).</li>
 *   <li>{@link #reconcileAfterUpload(UUID, String)} — at CONSUME time, i.e.
 *       when a feature (today: only {@code OrganizationBrandingService})
 *       persists the marker into a business record. This flow has no
 *       dedicated "finalize" endpoint, so the record-write IS the first point
 *       after the bytes exist where the server can act on them again. Total
 *       usage is recomputed from MinIO's own real sizes — including the
 *       object just uploaded — so a presign-time declared-size lie is caught
 *       here instead of silently standing.</li>
 * </ol>
 *
 * <p><strong>SUPER_ADMIN uploads are exempt exactly when they're
 * PLATFORM uploads</strong> ({@code orgId == null} — lesson media, no org
 * prefix, nothing to bill). A SUPER_ADMIN uploading WITH an {@code orgId}
 * (e.g. support acting on an org's branding) still lands under that org's
 * {@code org/<orgId>/} prefix and consumes that org's quota identically to an
 * ORG_ADMIN doing it — the quota is a property of the STORAGE PATH, not of
 * who called it, and the calling code has no way to attribute platform
 * storage to any one org in the first place.
 */
@Service
public class OrgStorageQuotaService implements MediaQuotaPort {

    private static final Logger log = LoggerFactory.getLogger(OrgStorageQuotaService.class);
    private static final String MINIO_SCHEME = "minio://";

    private final MinioClient internalClient;
    private final MediaProperties props;
    private final OrgStorageQuotaRepository quotaRepository;

    public OrgStorageQuotaService(
            @Qualifier("minioInternal") MinioClient internalClient,
            MediaProperties props,
            OrgStorageQuotaRepository quotaRepository) {
        this.internalClient = internalClient;
        this.props = props;
        this.quotaRepository = quotaRepository;
    }

    /**
     * Enforcement at upload INITIATION. {@code incomingBytes} is added to the
     * org's current real usage; if that would exceed the effective quota the
     * upload is refused before any bytes are accepted (multipart) or before a
     * presigned URL is even issued (presign).
     *
     * @throws IllegalOperationException (house convention for "well-formed
     *         request, refused by current account state" — 409, same mapping
     *         as e.g. {@code PipelineService}'s publish-state refusals) when
     *         the org has no room for this upload.
     */
    public void requireCapacity(UUID orgId, long incomingBytes) {
        long usage = totalUsageBytes(orgId);
        long quota = effectiveQuotaBytes(orgId);
        if (usage + incomingBytes > quota) {
            throw new IllegalOperationException(
                    "Organization storage quota exceeded: " + humanReadable(usage) + " used of "
                            + humanReadable(quota) + " quota; this upload needs "
                            + humanReadable(incomingBytes) + " more.");
        }
    }

    /**
     * Reconciliation at upload CONSUME time — see the class javadoc. Deletes
     * the object and refuses the write when the org's REAL, MinIO-recorded
     * usage (which already includes this object, since it has already been
     * uploaded by the time a marker exists to consume) is over quota.
     */
    @Override
    public void reconcileAfterUpload(UUID orgId, String marker) {
        long usage = totalUsageBytes(orgId);
        long quota = effectiveQuotaBytes(orgId);
        if (usage > quota) {
            deleteObject(marker);
            throw new IllegalOperationException(
                    "This upload pushed organization storage to " + humanReadable(usage)
                            + ", over the " + humanReadable(quota) + " quota. The file was not saved — "
                            + "free up space or contact support to raise the quota.");
        }
    }

    /**
     * Sum of every object's real, MinIO-recorded size under {@code org/<orgId>/}
     * in the configured bucket. The live source of truth for "how much has
     * this org stored" — see the class javadoc for why there is no counter.
     */
    long totalUsageBytes(UUID orgId) {
        String prefix = "org/" + orgId + "/";
        long total = 0;
        try {
            for (Result<Item> result : internalClient.listObjects(
                    ListObjectsArgs.builder()
                            .bucket(props.getBucket())
                            .prefix(prefix)
                            .recursive(true)
                            .build())) {
                total += result.get().size();
            }
        } catch (Exception ex) {
            throw new MediaUploadException(
                    "Failed to compute organization storage usage: " + ex.getMessage(), ex);
        }
        return total;
    }

    /** The org's own override if set, else the platform default. */
    long effectiveQuotaBytes(UUID orgId) {
        Long override = quotaRepository.quotaOverrideBytes(orgId);
        return override != null ? override : props.getOrgDefaultQuotaBytes();
    }

    private void deleteObject(String marker) {
        String prefix = MINIO_SCHEME + props.getBucket() + "/";
        if (marker == null || !marker.startsWith(prefix)) {
            log.warn("Cannot delete over-quota object: not a resolvable marker for this bucket: {}", marker);
            return;
        }
        String objectKey = marker.substring(prefix.length());
        try {
            internalClient.removeObject(
                    RemoveObjectArgs.builder().bucket(props.getBucket()).object(objectKey).build());
        } catch (Exception ex) {
            // Best-effort cleanup: the write is refused either way, so a delete
            // failure here must not turn one refusal into an unrelated 500 — it
            // only leaves one orphan object for the next reconcile/cleanup pass.
            log.warn("Failed to delete over-quota object {}: {}", objectKey, ex.getMessage());
        }
    }

    private static String humanReadable(long bytes) {
        double mib = bytes / (1024.0 * 1024.0);
        return String.format(Locale.ROOT, "%.1f MiB", mib);
    }
}
