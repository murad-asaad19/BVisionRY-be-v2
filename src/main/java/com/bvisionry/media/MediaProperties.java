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
}
