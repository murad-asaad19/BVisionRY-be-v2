package com.bvisionry.media;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds all {@code bvisionry.minio.*} keys from {@code application.properties}.
 *
 * <ul>
 *   <li>{@code internal-endpoint} — reachable by the backend (e.g. {@code http://minio:9000} in compose)</li>
 *   <li>{@code public-endpoint}   — reachable by browsers (e.g. {@code http://localhost:9000})</li>
 * </ul>
 */
@ConfigurationProperties(prefix = "bvisionry.minio")
public class MediaProperties {

    private String internalEndpoint = "http://localhost:9000";
    private String publicEndpoint   = "http://localhost:9000";
    private String accessKey        = "minio";
    private String secretKey        = "minio123";
    private String bucket           = "bvisionry-media";
    private int    presignedExpiryMinutes = 60;
    /**
     * S3 region for the clients. Setting it explicitly makes the SDK skip the
     * GetBucketLocation network call when computing presigned URLs — otherwise
     * the public client (a browser-facing host like {@code localhost:9000}) is
     * contacted from inside the backend container and presigning fails. MinIO's
     * default region is {@code us-east-1}.
     */
    private String region           = "us-east-1";
    /**
     * Upper bound on a server-side external-asset fetch ({@code MediaService.fetchBytes} over
     * http/https) so a misconfigured or hostile URL can't exhaust memory. The body is streamed
     * and the fetch aborts as soon as the ceiling is crossed.
     */
    private long externalFetchMaxBytes = 25L * 1024 * 1024;
    /**
     * SSRF guard escape hatch: when {@code false} (default, all real profiles) external media
     * fetches refuse hosts that resolve to loopback / private / link-local addresses. Tests set
     * {@code true} to exercise the fetch path against a localhost fixture server.
     */
    private boolean externalFetchAllowPrivate = false;
    /**
     * Platform-wide default ceiling on an org's total object-storage usage
     * (roadmap §11 — uploads were unbounded in count). Bytes, not MiB/GiB, to
     * match every other size field in this class. An org's own
     * {@code organizations.storage_quota_bytes} (V160), when set, overrides
     * this per-org; NULL there means "use this default". 2 GiB.
     */
    private long orgDefaultQuotaBytes = 2L * 1024 * 1024 * 1024;

    // ----- getters / setters -----

    public String getInternalEndpoint() { return internalEndpoint; }
    public void setInternalEndpoint(String internalEndpoint) { this.internalEndpoint = internalEndpoint; }

    public String getPublicEndpoint() { return publicEndpoint; }
    public void setPublicEndpoint(String publicEndpoint) { this.publicEndpoint = publicEndpoint; }

    public String getAccessKey() { return accessKey; }
    public void setAccessKey(String accessKey) { this.accessKey = accessKey; }

    public String getSecretKey() { return secretKey; }
    public void setSecretKey(String secretKey) { this.secretKey = secretKey; }

    public String getBucket() { return bucket; }
    public void setBucket(String bucket) { this.bucket = bucket; }

    public int getPresignedExpiryMinutes() { return presignedExpiryMinutes; }
    public void setPresignedExpiryMinutes(int presignedExpiryMinutes) { this.presignedExpiryMinutes = presignedExpiryMinutes; }

    public String getRegion() { return region; }
    public void setRegion(String region) { this.region = region; }

    public long getExternalFetchMaxBytes() { return externalFetchMaxBytes; }
    public void setExternalFetchMaxBytes(long externalFetchMaxBytes) { this.externalFetchMaxBytes = externalFetchMaxBytes; }

    public boolean isExternalFetchAllowPrivate() { return externalFetchAllowPrivate; }
    public void setExternalFetchAllowPrivate(boolean externalFetchAllowPrivate) { this.externalFetchAllowPrivate = externalFetchAllowPrivate; }

    public long getOrgDefaultQuotaBytes() { return orgDefaultQuotaBytes; }
    public void setOrgDefaultQuotaBytes(long orgDefaultQuotaBytes) { this.orgDefaultQuotaBytes = orgDefaultQuotaBytes; }
}
