package com.bvisionry.common.media;

/**
 * Shared-kernel view of "turn a stored media marker into something a browser
 * can load", consumed by feature packages that persist a {@code minio://}
 * marker but must not depend on the {@code media} package directly.
 *
 * <p>Exactly the seam {@code OrgHierarchyPort} is: features may depend on
 * {@code common}, never the reverse, and {@code ArchitectureRulesTest} rule 1
 * freezes every existing feature→feature edge, so a NEW one (here:
 * {@code organization} → {@code media}) fails the build. The alternative — a
 * bespoke branding-upload/read endpoint inside {@code organization} — cannot be
 * built for the same reason, and the frozen-violations store is never written.
 *
 * <p>Implemented by {@code MediaService} in the {@code media} package. The
 * signature is deliberately String-only so nothing about MinIO leaks into the
 * kernel.
 */
public interface MediaUrlPort {

    /**
     * Resolves a stored value to a loadable URL: a {@code minio://bucket/key}
     * marker becomes a fresh short-lived presigned GET URL; anything else
     * (external HTTPS, HLS manifest, {@code null}) is returned unchanged.
     */
    String resolveUrl(String stored);
}
