package com.bvisionry.media;

import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
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

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.security.CurrentUserAccessor;

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
public class MediaService implements com.bvisionry.common.media.MediaUrlPort {

    private static final Logger log = LoggerFactory.getLogger(MediaService.class);
    private static final String MINIO_SCHEME = "minio://";
    /**
     * Key prefix for org-scoped (white-label branding) uploads:
     * {@code org/<orgId>/branding/…}. The organization feature validates a
     * persisted marker against this shape for its OWN org id before storing it,
     * which is what stops an ORG_ADMIN persisting a marker that would presign a
     * GET for another tenant's objects. Shared with that validation only as a
     * FORMAT — no code crosses the feature line.
     */
    static final String ORG_KEY_PREFIX = "org/";
    static final String ORG_BRANDING_SEGMENT = "/branding";
    /** Redirect-hop ceiling for external fetches; each hop is re-validated against the SSRF guard. */
    private static final int MAX_EXTERNAL_REDIRECTS = 5;
    /** Shared, thread-safe client for {@link #fetchExternal(String)} — keeps connection reuse across fetches. */
    private static final HttpClient EXTERNAL_HTTP_CLIENT = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(10))
            .followRedirects(HttpClient.Redirect.NEVER)
            .build();

    private final MinioClient internalClient;
    private final MinioClient publicClient;
    private final MediaProperties props;
    private final OrgStorageQuotaService orgStorageQuota;
    private final CurrentUserAccessor currentUser;
    private final AtomicBoolean bucketEnsured = new AtomicBoolean(false);

    public MediaService(
            @Qualifier("minioInternal") MinioClient internalClient,
            @Qualifier("minioPublic")   MinioClient publicClient,
            MediaProperties props,
            OrgStorageQuotaService orgStorageQuota,
            CurrentUserAccessor currentUser) {
        this.internalClient  = internalClient;
        this.publicClient    = publicClient;
        this.props           = props;
        this.orgStorageQuota = orgStorageQuota;
        this.currentUser     = currentUser;
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
     * @param file  the uploaded file
     * @param kind  a path prefix / folder name (e.g. {@code "video"}, {@code "asset"})
     * @param orgId when non-null the upload is ORG-SCOPED: it must be
     *              {@code kind=image} and it lands under
     *              {@code org/<orgId>/branding/}. This is the only shape an
     *              ORG_ADMIN is authorized to reach (see MediaController).
     * @return the {@code minio://bucket/objectKey} marker to persist in the database
     */
    public String upload(MultipartFile file, String kind, UUID orgId) {
        MediaUploadPolicy.requireOrgScopedKindIsImage(kind, orgId);
        MediaUploadPolicy.requireCoachKindIsImage(kind, currentUser.require().role());
        String contentType = MediaUploadPolicy.validate(
                kind, file.getContentType(), file.getOriginalFilename(), file.getSize());
        if (orgId != null) {
            // The server received the real bytes on this path, so the declared
            // size IS the real size — no reconciliation is needed afterwards,
            // unlike the presigned path (see presignUpload/reconcileAfterUpload).
            orgStorageQuota.requireCapacity(orgId, file.getSize());
        }
        lazyEnsureBucket();

        String objectKey = buildObjectKey(kind, file.getOriginalFilename(), orgId);

        try (InputStream is = file.getInputStream()) {
            internalClient.putObject(
                    PutObjectArgs.builder()
                            .bucket(props.getBucket())
                            .object(objectKey)
                            .stream(is, file.getSize(), -1)
                            .contentType(contentType)
                            .build());
        } catch (Exception ex) {
            throw new MediaUploadException("Failed to upload file to MinIO: " + ex.getMessage(), ex);
        }

        return MINIO_SCHEME + props.getBucket() + "/" + objectKey;
    }

    /**
     * Browser-direct upload coordinates returned by {@link #presignUpload}.
     *
     * @param uploadUrl   short-lived presigned PUT URL the browser uploads bytes to directly
     * @param marker      the {@code minio://bucket/objectKey} value to persist in the DB
     * @param previewUrl  a presigned GET URL usable for preview once the PUT completes
     * @param contentType the NORMALIZED content type the server validated. On an
     *                    org-scoped presign this value is bound into the
     *                    signature, so the browser's PUT must send exactly it or
     *                    the upload is refused by the object store; returning it
     *                    is what lets a client comply (its own {@code File.type}
     *                    can be blank or differently-cased). The declared
     *                    {@code sizeBytes} is bound the same way on that path —
     *                    nothing is returned for it because the client already
     *                    knows the number it sent, and every HTTP client sets
     *                    {@code Content-Length} from the body automatically.
     */
    public record PresignedUpload(String uploadUrl, String marker, String previewUrl, String contentType) {}

    /**
     * Issues a presigned PUT URL so the browser uploads a file <strong>directly</strong> to
     * MinIO, never routing the bytes through this application server.
     *
     * <p>This is the upload-side counterpart to {@link #resolveUrl(String)}: the same
     * public-endpoint client signs a short-lived URL the browser can reach. It exists because
     * fronting platforms cap proxied request bodies — e.g. Vercel Functions reject anything over
     * 4.5MB with a 413 before it reaches the backend — a ceiling the server-proxied
     * {@link #upload(MultipartFile, String)} path can never exceed. A direct PUT sidesteps every
     * intermediate body-size limit; only MinIO ever sees the bytes.
     *
     * <p>The returned {@code marker} has the identical format to {@link #upload}, so
     * {@link #resolveUrl(String)} (browser preview) and {@link #download(String)} (server-side
     * fetch, e.g. emailing the lead-magnet PDF) work against it unchanged once the object lands.
     *
     * @param kind        path prefix / folder (e.g. {@code "pdf"}, {@code "video"}); defaults to {@code "asset"} when blank
     * @param filename    original filename, used only to build a readable object key
     * @param contentType the file's MIME type
     * @param orgId       when non-null the presign is ORG-SCOPED: it must be
     *                    {@code kind=image}, it lands under
     *                    {@code org/<orgId>/branding/}, and the normalized
     *                    content type is BOUND INTO THE SIGNATURE (see below)
     * @return the presigned PUT URL, the {@code minio://} marker to persist, a presigned GET
     *         preview URL, and the content type the signature was computed with
     */
    public PresignedUpload presignUpload(String kind, String filename, String contentType, UUID orgId) {
        return presignUpload(kind, filename, contentType, null, orgId);
    }

    /**
     * Same as {@link #presignUpload(String, String, String, UUID)}, plus a
     * CLIENT-DECLARED {@code sizeBytes} the org-scoped path ({@code orgId != null})
     * budgets against the org's storage quota — required there, since a presign with
     * no declared size gives the quota nothing to check; ignored otherwise.
     *
     * <p>On the org-scoped path that declaration is also BOUND INTO THE SIGNATURE as
     * {@code Content-Length}, exactly the way {@code Content-Type} is, so it stops
     * being a claim: the object store refuses any PUT whose body length differs.
     * Without that binding the quota was decorative — an ORG_ADMIN calling the API
     * directly could presign N times declaring one byte each, PUT arbitrary bytes
     * against every URL, and never consume the markers that would trigger
     * {@link OrgStorageQuotaService#reconcileAfterUpload}. Reconciliation stays as
     * the second line of defence (it also catches objects written before this bound).
     *
     * @param sizeBytes declared upload size; required and must be positive
     *                  when {@code orgId != null}, otherwise unused
     */
    public PresignedUpload presignUpload(
            String kind, String filename, String contentType, Long sizeBytes, UUID orgId) {
        String resolvedKind = (kind == null || kind.isBlank()) ? "asset" : kind;
        String callerRole = currentUser.require().role();
        MediaUploadPolicy.requireOrgScopedKindIsImage(resolvedKind, orgId);
        MediaUploadPolicy.requireCoachKindIsImage(resolvedKind, callerRole);
        // Which callers must have their upload SIZE-BOUND: the org-scoped branding
        // path (quota) AND the COACH profile-photo path. Both are lower-trust than
        // the historical SUPER_ADMIN/INSTRUCTOR lesson-media dropzone, which alone
        // keeps the unbound platform path (its client sends no declared size and
        // its files are legitimately large). For a bound caller the declared size
        // is (a) checked against the per-kind cap here and (b) signed into the PUT
        // as Content-Length below, so the object store refuses a body of any other
        // length — without which a coach could presign kind=image and PUT an
        // arbitrarily large object into the shared platform bucket, unmetered.
        boolean bindSize = orgId != null || MediaUploadPolicy.COACH_ROLE.equals(callerRole);
        if (bindSize && (sizeBytes == null || sizeBytes <= 0)) {
            throw new BadRequestException(
                    "A positive declared size (sizeBytes) is required to presign this upload");
        }
        String normalizedType = MediaUploadPolicy.validate(
                resolvedKind, contentType, filename, bindSize ? sizeBytes : -1);
        if (orgId != null) {
            orgStorageQuota.requireCapacity(orgId, sizeBytes);
        }
        lazyEnsureBucket();

        String objectKey    = buildObjectKey(resolvedKind, filename, orgId);
        String marker       = MINIO_SCHEME + props.getBucket() + "/" + objectKey;

        final String uploadUrl;
        try {
            GetPresignedObjectUrlArgs.Builder args = GetPresignedObjectUrlArgs.builder()
                    .method(Method.PUT)
                    .bucket(props.getBucket())
                    .object(objectKey)
                    .expiry(props.getPresignedExpiryMinutes() * 60, TimeUnit.SECONDS);
            if (bindSize) {
                // CONTENT-TYPE AND CONTENT-LENGTH PINNING, size-bound paths only
                // (org-scoped branding + coach profile photo). Both go into
                // extraHeaders, which makes them SIGNED headers, so the PUT is
                // refused unless it carries exactly these values.
                //
                // Content-Type: without it the validated "image/png" is only ever a
                // claim in the presign REQUEST — the browser could PUT the same object
                // as text/html and the object store would serve stored markup from the
                // object-store origin off a presigned GET, the same stored-XSS channel
                // image/svg+xml is excluded to avoid.
                //
                // Content-Length: without it the declared sizeBytes the quota was
                // budgeted against is likewise only a claim. Signing it makes the
                // quota decision binding on the bytes actually written, instead of
                // resting entirely on a reconciliation that only fires if the caller
                // later chooses to persist the marker.
                //
                // Scoped to org-scoped presigns rather than applied globally because
                // the existing lesson-media client PUTs its own raw File.type (and
                // omits the header entirely when that is blank) and sends no declared
                // size on that path, so pinning either for every caller would break
                // uploads that work today. That gap is real but pre-existing and
                // SUPER_ADMIN/INSTRUCTOR-only; closing it means changing the shared
                // dropzone, which is outside this ticket.
                args.extraHeaders(com.google.common.collect.ImmutableMultimap.of(
                        "Content-Type", normalizedType,
                        "Content-Length", String.valueOf(sizeBytes)));
            }
            uploadUrl = publicClient.getPresignedObjectUrl(args.build());
        } catch (Exception ex) {
            throw new MediaUploadException("Failed to presign upload URL: " + ex.getMessage(), ex);
        }

        return new PresignedUpload(uploadUrl, marker, resolveUrl(marker), normalizedType);
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
    @Override
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

    /**
     * Fetches an admin-configured external asset with two hard safety bounds:
     *
     * <ul>
     *   <li><strong>SSRF guard</strong> — redirects are followed manually
     *       ({@code Redirect.NEVER}) so EVERY hop's host is validated against
     *       {@link #requireExternallyRoutableHost(URI)}; an allowed first hop
     *       can't bounce the fetch into a private network.</li>
     *   <li><strong>Byte ceiling</strong> — the body is streamed and reading
     *       stops one byte past {@code externalFetchMaxBytes}; an oversized or
     *       endless response can never be buffered whole.</li>
     * </ul>
     */
    private byte[] fetchExternal(String url) {
        URI target;
        try {
            target = URI.create(url);
        } catch (IllegalArgumentException ex) {
            throw new MediaUploadException("Invalid external media URL: " + url, ex);
        }

        long maxBytes = props.getExternalFetchMaxBytes();
        for (int hop = 0; hop <= MAX_EXTERNAL_REDIRECTS; hop++) {
            requireExternallyRoutableHost(target);

            final HttpRequest request;
            try {
                request = HttpRequest.newBuilder(target)
                        .timeout(Duration.ofSeconds(30))
                        .GET()
                        .build();
            } catch (IllegalArgumentException ex) {
                throw new MediaUploadException("Invalid external media URL: " + target, ex);
            }

            try {
                HttpResponse<InputStream> response =
                        EXTERNAL_HTTP_CLIENT.send(request, HttpResponse.BodyHandlers.ofInputStream());
                int status = response.statusCode();

                if (status / 100 == 3) {
                    String location = response.headers().firstValue("Location").orElse(null);
                    try (InputStream ignored = response.body()) { /* drain/close redirect body */ }
                    if (location == null) {
                        throw new MediaUploadException(
                                "External media fetch returned HTTP " + status + " without a Location for " + url);
                    }
                    target = target.resolve(location);
                    continue;
                }
                if (status / 100 != 2) {
                    try (InputStream ignored = response.body()) { /* drain/close error body */ }
                    throw new MediaUploadException(
                            "External media fetch returned HTTP " + status + " for " + url);
                }

                try (InputStream body = response.body()) {
                    int readLimit = (int) Math.min(maxBytes + 1, Integer.MAX_VALUE);
                    byte[] bytes = body.readNBytes(readLimit);
                    if (bytes.length > maxBytes) {
                        throw new MediaUploadException(
                                "External media exceeds the " + (maxBytes / (1024 * 1024)) + "MB limit: " + url);
                    }
                    return bytes;
                }
            } catch (IOException ex) {
                throw new MediaUploadException(
                        "Failed to fetch external media from " + url + ": " + ex.getMessage(), ex);
            } catch (InterruptedException ex) {
                Thread.currentThread().interrupt();
                throw new MediaUploadException("Interrupted fetching external media from " + url, ex);
            }
        }
        throw new MediaUploadException(
                "Too many redirects (max " + MAX_EXTERNAL_REDIRECTS + ") fetching external media from " + url);
    }

    /**
     * SSRF guard for {@link #fetchExternal(String)}: only http(s) schemes, and —
     * unless {@code externalFetchAllowPrivate} is set (tests only) — the host
     * must not resolve to any loopback / private / link-local / CGNAT address.
     *
     * <p>ponytail: the address is validated at check time and the HTTP client
     * re-resolves at connect time, so a hostile DNS record flipping between the
     * two (DNS rebinding) is a known ceiling. The feature is SUPER_ADMIN-gated
     * admin configuration; pin the resolved address into the connection if this
     * ever opens to lower-trust input.</p>
     */
    private void requireExternallyRoutableHost(URI uri) {
        String scheme = uri.getScheme();
        if (scheme == null
                || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            throw new MediaUploadException("Unsupported external media scheme: " + uri);
        }
        if (props.isExternalFetchAllowPrivate()) {
            return;
        }
        String host = uri.getHost();
        if (host == null) {
            throw new MediaUploadException("External media URL has no host: " + uri);
        }
        final InetAddress[] addresses;
        try {
            addresses = InetAddress.getAllByName(host);
        } catch (UnknownHostException ex) {
            throw new MediaUploadException("Cannot resolve external media host: " + host, ex);
        }
        for (InetAddress address : addresses) {
            if (isPrivateAddress(address)) {
                throw new MediaUploadException(
                        "External media host '" + host + "' resolves to a private address and is not allowed");
            }
        }
    }

    private static boolean isPrivateAddress(InetAddress address) {
        if (address.isLoopbackAddress() || address.isAnyLocalAddress()
                || address.isLinkLocalAddress() || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = address.getAddress();
        if (raw.length == 16 && (raw[0] & 0xFE) == 0xFC) {
            return true; // IPv6 unique-local fc00::/7
        }
        return raw.length == 4 && (raw[0] & 0xFF) == 100 && (raw[1] & 0xC0) == 64; // CGNAT 100.64/10
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
     * Builds the object key shared by the server-proxied ({@link #upload}) and
     * browser-direct ({@link #presignUpload}) upload paths, so the key format
     * and filename sanitisation can never drift between them.
     *
     * <p>Platform uploads keep the historical {@code kind/uuid-filename} shape.
     * ORG-SCOPED uploads instead land under {@code org/<orgId>/branding/…} —
     * the tenant prefix that makes the stored marker checkable against the
     * caller's own org, which is the whole IDOR defence for white-label logos.
     */
    private String buildObjectKey(String kind, String filename, UUID orgId) {
        String folder = orgId == null
                ? kind
                : ORG_KEY_PREFIX + orgId + ORG_BRANDING_SEGMENT;
        return folder + "/" + UUID.randomUUID() + "-" + sanitizeFilename(filename);
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
