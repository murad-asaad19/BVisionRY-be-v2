package com.bvisionry.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Service;
import org.springframework.web.multipart.MultipartFile;

import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.GetObjectResponse;
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
    /** Upper bound on a server-side external-asset fetch so a misconfigured URL can't exhaust memory. */
    private static final long MAX_EXTERNAL_BYTES = 25L * 1024 * 1024;

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

        final MinioObjectRef ref;
        try {
            ref = parseMinioMarker(stored);
        } catch (MediaUploadException ex) {
            // Malformed / disallowed-bucket marker: log and fall back to the raw
            // value rather than presigning against the wrong store.
            log.warn("Cannot resolve media marker: {}", ex.getMessage());
            return stored;
        }

        try {
            return publicClient.getPresignedObjectUrl(
                    GetPresignedObjectUrlArgs.builder()
                            .method(Method.GET)
                            .bucket(props.getBucket())
                            .object(ref.objectKey())
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
    // Download (server-side byte fetch)
    // -------------------------------------------------------------------------

    /**
     * Reads the full bytes of a stored {@code minio://bucket/objectKey} marker via
     * the internal client. Used server-side when the file must travel inside the
     * application rather than to the browser — e.g. attaching the lead-magnet PDF
     * to an outgoing email.
     *
     * <p>Applies the same bucket guard as {@link #resolveUrl(String)}: a marker
     * pointing at any bucket other than the configured one is rejected. Only
     * {@code minio://} markers are supported; pass external URLs through a
     * different fetch path.
     *
     * @throws MediaUploadException if the marker is not a usable minio marker or
     *                              the object cannot be read.
     */
    public byte[] download(String stored) {
        MinioObjectRef ref = parseMinioMarker(stored);
        try (GetObjectResponse response = internalClient.getObject(
                GetObjectArgs.builder()
                        .bucket(props.getBucket())
                        .object(ref.objectKey())
                        .build())) {
            return response.readAllBytes();
        } catch (Exception ex) {
            throw new MediaUploadException("Failed to download object from MinIO: " + ex.getMessage(), ex);
        }
    }

    /**
     * Reads the full bytes behind any stored marker for server-side use (e.g.
     * attaching the lead-magnet PDF to an outgoing email):
     *
     * <ul>
     *   <li>{@code minio://} markers are read via {@link #download(String)}.</li>
     *   <li>{@code http(s)://} URLs are fetched over HTTP — admin-configured
     *       assets hosted off-platform, which {@link #resolveUrl(String)} already
     *       exposes to the browser, must also be fetchable here.</li>
     * </ul>
     *
     * <p>This is the symmetric server-side counterpart to {@link #resolveUrl},
     * so the two marker kinds the admin UI accepts (uploaded file or external
     * URL) both work end-to-end instead of the URL form silently failing.
     *
     * @throws MediaUploadException if the marker is unsupported or cannot be read.
     */
    public byte[] fetchBytes(String marker) {
        if (marker == null || marker.isBlank()) {
            throw new MediaUploadException("No media marker provided");
        }
        if (marker.startsWith(MINIO_SCHEME)) {
            return download(marker);
        }
        if (marker.startsWith("http://") || marker.startsWith("https://")) {
            return fetchExternal(marker);
        }
        throw new MediaUploadException(
                "Unsupported media marker (expected minio:// or http(s)://): " + marker);
    }

    private byte[] fetchExternal(String url) {
        HttpClient client = HttpClient.newBuilder()
                .connectTimeout(Duration.ofSeconds(10))
                .followRedirects(HttpClient.Redirect.NORMAL)
                .build();

        final HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(url))
                    .timeout(Duration.ofSeconds(30))
                    .GET()
                    .build();
        } catch (IllegalArgumentException ex) {
            throw new MediaUploadException("Invalid external media URL: " + url, ex);
        }

        try {
            HttpResponse<byte[]> response = client.send(request, HttpResponse.BodyHandlers.ofByteArray());
            if (response.statusCode() / 100 != 2) {
                throw new MediaUploadException(
                        "External media fetch returned HTTP " + response.statusCode() + " for " + url);
            }
            byte[] body = response.body();
            if (body.length > MAX_EXTERNAL_BYTES) {
                throw new MediaUploadException(
                        "External media exceeds the " + (MAX_EXTERNAL_BYTES / (1024 * 1024)) + "MB limit: " + url);
            }
            return body;
        } catch (IOException ex) {
            throw new MediaUploadException("Failed to fetch external media from " + url + ": " + ex.getMessage(), ex);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            throw new MediaUploadException("Interrupted fetching external media from " + url, ex);
        }
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** A parsed, bucket-validated {@code minio://bucket/objectKey} reference. */
    private record MinioObjectRef(String bucket, String objectKey) {}

    /**
     * Parses and bucket-guards a {@code minio://bucket/objectKey} marker. Shared
     * by {@link #resolveUrl(String)} (browser read) and {@link #download(String)}
     * (server-side fetch) so the marker format and the security guard can never
     * drift between the two paths.
     *
     * <p>SECURITY: rejects any marker referencing a bucket other than the one
     * this service owns. A privileged author could otherwise persist a marker
     * like {@code minio://other-internal-bucket/secret} and have it resolved or
     * downloaded against an arbitrary store.
     *
     * @throws MediaUploadException if the value is not a minio marker, is
     *                              malformed, or references a different bucket.
     */
    private MinioObjectRef parseMinioMarker(String stored) {
        if (stored == null || !stored.startsWith(MINIO_SCHEME)) {
            throw new MediaUploadException("Not a minio:// marker: " + stored);
        }
        String withoutScheme = stored.substring(MINIO_SCHEME.length()); // "bucket/objectKey"
        int slashIdx = withoutScheme.indexOf('/');
        if (slashIdx < 0) {
            throw new MediaUploadException("Malformed minio:// marker (no slash after bucket): " + stored);
        }
        String markerBucket = withoutScheme.substring(0, slashIdx);
        String objectKey    = withoutScheme.substring(slashIdx + 1);
        if (!props.getBucket().equals(markerBucket)) {
            throw new MediaUploadException(
                    "Rejecting minio:// marker referencing unexpected bucket '" + markerBucket
                            + "' (configured bucket is '" + props.getBucket() + "')");
        }
        return new MinioObjectRef(markerBucket, objectKey);
    }

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
