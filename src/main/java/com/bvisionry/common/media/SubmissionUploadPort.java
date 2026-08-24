package com.bvisionry.common.media;

import java.util.UUID;

/**
 * Shared-kernel view of "presign a browser-direct upload for a member's task
 * attachment", consumed by the {@code programflow} feature which must not
 * depend on the {@code media} package directly.
 *
 * <p>Exactly the seam {@link MediaUrlPort} and {@link MediaQuotaPort} are (see
 * their javadoc): features may depend on {@code common}, never the reverse,
 * and {@code ArchitectureRulesTest} rule 1 freezes every existing
 * feature→feature edge, so a NEW {@code programflow} → {@code media} edge
 * fails the build.
 *
 * <p>Implemented by {@code MediaService} in the {@code media} package. The
 * upload lands under the caller org's own key prefix
 * ({@code org/<orgId>/submissions/<userId>/…}) so it counts against the org's
 * storage quota and so the persisted marker is checkable against the member
 * who uploaded it — the same key-prefix IDOR defence the branding path uses.
 * The declared size and normalized content type are BOUND INTO THE SIGNATURE
 * (as Content-Length / Content-Type), so the object store refuses a PUT whose
 * body differs from what the quota was budgeted against.
 */
public interface SubmissionUploadPort {

    /**
     * Browser-direct upload coordinates.
     *
     * @param uploadUrl   short-lived presigned PUT URL the browser uploads the bytes to
     * @param marker      the {@code minio://bucket/objectKey} value to persist in the answer
     * @param previewUrl  a presigned GET URL usable once the PUT completes
     * @param contentType the normalized content type the signature was computed
     *                    with — the PUT must send exactly this value
     */
    record PresignedSubmissionUpload(
            String uploadUrl, String marker, String previewUrl, String contentType) {}

    /**
     * Issues a size- and type-bound presigned PUT URL for a member's task
     * attachment, after checking the org's storage quota against the declared
     * size. Callers authorize FIRST (the member's access to the task); this
     * method's own contribution is validation, quota, and the key prefix.
     *
     * @throws RuntimeException (house conventions) on unknown/blank content
     *         type, a non-positive size, a size over the per-kind cap, or an
     *         org over its storage quota.
     */
    PresignedSubmissionUpload presignSubmissionUpload(
            UUID orgId, UUID userId, String filename, String contentType, long sizeBytes);
}
