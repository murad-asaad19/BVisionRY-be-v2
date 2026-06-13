package com.bvisionry.media;

import java.io.InputStream;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.http.Method;
import jakarta.annotation.PostConstruct;

/**
 * Handles all MinIO / S3-compatible object-store operations for lesson media.
 *
 * <p><strong>Upload path</strong>: {@link #upload(MultipartFile, String)} streams the file to MinIO
 * using the internal client and returns a {@code minio://bucket/objectKey} marker suitable for
 * storing in {@code content.video_url} or {@code content.asset_url}.</p>
 *
 * <p><strong>Read path</strong>: {@link #resolveUrl(String)} detects the {@code minio://} prefix
 * and generates a fresh presigned GET URL via the public client so browsers can load the asset
 * directly.  Non-minio URLs (external HLS, plain HTTPS) are returned unchanged.</p>
 *
 * <p>Bucket auto-creation is attempted at startup; if MinIO is briefly unavailable the failure is
 * logged as a warning and the application continues.  A lazy check is performed on the first
 * upload so uploads succeed once MinIO becomes reachable.</p>
 */
@Service
public class MediaService {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final String MINIO_SCHEME = "minio://";

    private final MinioClient internalClient;
    private final MinioClient publicClient;
    private final MediaProperties props;
    private final AtomicBoolean bucketEnsured = new AtomicBoolean(false);

    public MediaService(
            @Qualifier("minioInternal") MinioClient internalClient,
            @Qualifier("minioPublic")   MinioClient publicClient,
            MediaProperties props) {
        this.internalClient = internalClient;
        this.publicClient   = publicClient;
        this.props          = props;
    }

    // -------------------------------------------------------------------------
    // Startup
    // -------------------------------------------------------------------------

    @PostConstruct
    public void ensureBucket() {
        try {
            doEnsureBucket();
        } catch (Exception ex) {
            log.warn("MinIO bucket ensure failed at startup (MinIO may not be ready yet). " +
                     "Will retry lazily on first upload. bucket={} error={}", props.getBucket(), ex.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Upload
    // -------------------------------------------------------------------------

    /**
     * Uploads a multipart file to MinIO.
     *
     * @param file the uploaded file
     * @param kind a path prefix / folder name (e.g. {@code "video"}, {@code "asset"})
     * @return the {@code minio://bucket/objectKey} marker to persist in the database
     */
    public String upload(MultipartFile file, String kind) {
        lazyEnsureBucket();

        String sanitizedName = sanitizeFilename(file.getOriginalFilename());
        String objectKey     = kind + "/" + UUID.randomUUID() + "-" + sanitizedName;

        try (InputStream is = file.getInputStream()) {
            internalClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .stream(is, file.getSize(), -1)
                            .contentType(file.getContentType())
                            .build());
        } catch (Exception ex) {
            throw new MediaUploadException("Failed to upload file to MinIO: " + ex.getMessage(), ex);
        }

        return MINIO_SCHEME + props.getBucket() + "/" + objectKey;
    }

    // -------------------------------------------------------------------------
    // URL resolution
    // -------------------------------------------------------------------------

    /**
     * Resolves a stored URL value to something a browser can load.
     *
     * <ul>
     *   <li>If {@code stored} starts with {@code minio://}, a fresh presigned GET URL is
     *       generated using the public-endpoint client.</li>
     *   <li>Otherwise the value is returned unchanged (external HTTPS, HLS manifest, etc.).</li>
     *   <li>{@code null} is returned as-is.</li>
     * </ul>
     *
     * <p>This method is intentionally {@code public} so the orchestrator can call it from
     * {@code CatalogService} and {@code EnrollmentService} without this class being edited.</p>
     */
    public String resolveUrl(String stored) {
        if (stored == null || !stored.startsWith(MINIO_SCHEME)) {
            return stored;
        }

        // minio://bucket/objectKey  →  parse bucket and key
        String withoutScheme = stored.substring(MINIO_SCHEME.length()); // "bucket/objectKey"
        int slashIdx = withoutScheme.indexOf('/');
        if (slashIdx < 0) {
            log.warn("Malformed minio:// marker (no slash after bucket): {}", stored);
            return stored;
        }
        String markerBucket = withoutScheme.substring(0, slashIdx);
        String objectKey    = withoutScheme.substring(slashIdx + 1);

        // SECURITY: never presign against the bucket named in the stored marker.
        // A privileged author can persist videoUrl/assetUrl = minio://other-internal-bucket/secret,
        // which would otherwise hand every learner a working presigned GET into an arbitrary bucket.
        // The only bucket this service uploads to is props.getBucket(); reject any marker that
        // references a different bucket rather than silently resolving it against the wrong store.
        if (!props.getBucket().equals(markerBucket)) {
            log.warn("Rejecting minio:// marker referencing unexpected bucket '{}' (configured bucket is '{}'): {}",
                    markerBucket, props.getBucket(), stored);
            return stored;
        }

        try {
            return publicClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .expiry(props.getPresignedExpiryMinutes() * 60, TimeUnit.SECONDS)
                            .build());
        } catch (Exception ex) {
            log.error("Failed to generate presigned URL for {}: {}", stored, ex.getMessage());
            // Return the raw marker rather than null so callers can distinguish
            // "presign failed" from "no media set".
            return stored;
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void lazyEnsureBucket() {
        if (!bucketEnsured.get()) {
            try {
                doEnsureBucket();
            } catch (Exception ex) {
                throw new MediaUploadException("MinIO bucket is not available: " + ex.getMessage(), ex);
            }
        }
    }

    private void doEnsureBucket() throws Exception {
        boolean exists = internalClient.bucketExists(
                BucketExistsArgs.builder().bucket(props.getBucket()).build());
        if (!exists) {
            internalClient.makeBucket(
                    MakeBucketArgs.builder().bucket(props.getBucket()).build());
            log.info("MinIO bucket created: {}", props.getBucket());
        } else {
            log.debug("MinIO bucket already exists: {}", props.getBucket());
        }
        bucketEnsured.set(true);
    }

    /**
     * Strips path traversal components and replaces whitespace with underscores to produce a
     * safe filename segment suitable for use in an object key.
     */
    private static String sanitizeFilename(String original) {
        if (original == null || original.isBlank()) {
            return "file";
        }
        // Take only the last path component (handles Windows and POSIX separators)
        String basename = original.replaceAll(".*[/\\\\]", "");
        // Replace whitespace and any non-filename-safe chars with underscores
        return basename.replaceAll("[^a-zA-Z0-9._-]", "_");
    }
}
