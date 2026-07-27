package com.bvisionry.media;

import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import com.bvisionry.common.exception.BadRequestException;

/**
 * Server-side validation policy for media uploads — the single enforcement
 * point shared by the server-proxied path ({@code MediaService.upload}) and
 * the browser-direct path ({@code MediaService.presignUpload}).
 *
 * <p>Both paths validate the {@code kind} (which becomes the object-key folder)
 * against the closed set the web client uses, and the content type against a
 * per-kind allowlist. Notably absent everywhere: {@code text/html},
 * {@code image/svg+xml}, and script types — objects are served to browsers via
 * presigned GET URLs, so allowing markup would open a stored-XSS channel on
 * the object-store origin.</p>
 *
 * <p>Size caps apply to the server-proxied multipart path only: a presigned
 * PUT does not bind Content-Length, so the presign path cannot enforce a size
 * server-side (the fronting proxy and MinIO quotas are the bounds there).</p>
 */
final class MediaUploadPolicy {

    private MediaUploadPolicy() {}

    /** The only kind an org-scoped (white-label branding) upload may carry. */
    static final String ORG_SCOPED_KIND = "image";

    /**
     * Org-scoped uploads are branding logos and nothing else.
     *
     * <p>This is the enforcement half of widening {@code MediaController} to
     * admit ORG_ADMIN. Authorization admits an ORG_ADMIN <em>only</em> with an
     * {@code orgId} they pass {@code @orgAccess.isInOrg} for; this method
     * refuses any org-scoped upload whose kind is not {@code image}. Composed,
     * the two mean an ORG_ADMIN can upload images and nothing else — no videos,
     * no PDFs, no generic assets — on BOTH the multipart and the presign path,
     * because both call it.
     *
     * <p>Enforced here rather than in the {@code @PreAuthorize} expression on
     * purpose: the presign path carries {@code kind} in the request BODY, and
     * SpEL property access on a record is not something to bet an authorization
     * rule on. A plain Java guard behaves identically on both paths.
     */
    static void requireOrgScopedKindIsImage(String kind, java.util.UUID orgId) {
        if (orgId != null && !ORG_SCOPED_KIND.equals(kind)) {
            throw new BadRequestException(
                    "Org-scoped uploads must use kind '" + ORG_SCOPED_KIND + "', not '" + kind + "'");
        }
    }

    private record KindPolicy(Set<String> contentTypes, long maxUploadBytes) {}

    private static final long MB = 1024L * 1024;

    private static final Set<String> IMAGE_TYPES = Set.of(
            "image/png", "image/jpeg", "image/webp", "image/gif", "image/avif");

    private static final Set<String> VIDEO_TYPES = Set.of(
            "video/mp4", "video/webm", "video/quicktime", "video/x-m4v", "video/ogg",
            "application/vnd.apple.mpegurl", "application/x-mpegurl", "audio/mpegurl");

    private static final Set<String> PDF_TYPES = Set.of("application/pdf");

    /** Generic lesson attachments: docs, archives, data and audio — never markup or executables. */
    private static final Set<String> EXTRA_ASSET_TYPES = Set.of(
            "application/zip", "text/plain", "text/csv", "application/json",
            "application/msword",
            "application/vnd.openxmlformats-officedocument.wordprocessingml.document",
            "application/vnd.ms-excel",
            "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet",
            "application/vnd.ms-powerpoint",
            "application/vnd.openxmlformats-officedocument.presentationml.presentation",
            "audio/mpeg", "audio/mp4", "audio/wav", "audio/ogg");

    private static final Map<String, KindPolicy> KINDS = Map.of(
            "image", new KindPolicy(IMAGE_TYPES, 10 * MB),
            "pdf",   new KindPolicy(PDF_TYPES, 25 * MB),
            "video", new KindPolicy(VIDEO_TYPES, 50 * MB),
            "asset", new KindPolicy(assetTypes(), 50 * MB));

    /**
     * Extension fallback for browsers that submit a blank or generic type for
     * niche formats (an {@code .m3u8} playlist is the canonical case). Only
     * consulted when the declared type is empty or {@code application/octet-stream};
     * an explicit disallowed type is rejected, never "repaired".
     */
    private static final Map<String, String> EXTENSION_TYPES = Map.ofEntries(
            Map.entry("mp4", "video/mp4"),
            Map.entry("webm", "video/webm"),
            Map.entry("mov", "video/quicktime"),
            Map.entry("m4v", "video/x-m4v"),
            Map.entry("m3u8", "application/vnd.apple.mpegurl"),
            Map.entry("png", "image/png"),
            Map.entry("jpg", "image/jpeg"),
            Map.entry("jpeg", "image/jpeg"),
            Map.entry("webp", "image/webp"),
            Map.entry("gif", "image/gif"),
            Map.entry("avif", "image/avif"),
            Map.entry("pdf", "application/pdf"),
            Map.entry("zip", "application/zip"),
            Map.entry("txt", "text/plain"),
            Map.entry("csv", "text/csv"),
            Map.entry("json", "application/json"),
            Map.entry("mp3", "audio/mpeg"),
            Map.entry("m4a", "audio/mp4"),
            Map.entry("wav", "audio/wav"),
            Map.entry("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document"),
            Map.entry("xlsx", "application/vnd.openxmlformats-officedocument.spreadsheetml.sheet"),
            Map.entry("pptx", "application/vnd.openxmlformats-officedocument.presentationml.presentation"));

    private static Set<String> assetTypes() {
        Set<String> all = new HashSet<>(EXTRA_ASSET_TYPES);
        all.addAll(IMAGE_TYPES);
        all.addAll(VIDEO_TYPES);
        all.addAll(PDF_TYPES);
        return Set.copyOf(all);
    }

    /**
     * Validates the kind, content type and (when known) size of an upload.
     *
     * @param kind        object-key folder; must be one of the known kinds
     * @param contentType declared MIME type; blank/octet-stream falls back to the
     *                    filename extension
     * @param filename    original filename, used only for the extension fallback
     * @param sizeBytes   upload size, or {@code -1} when unknowable (presign path)
     * @return the normalized content type to store on the object
     * @throws BadRequestException when any of the three checks fails
     */
    static String validate(String kind, String contentType, String filename, long sizeBytes) {
        KindPolicy policy = KINDS.get(kind);
        if (policy == null) {
            throw new BadRequestException(
                    "Unknown media kind '" + kind + "' — expected one of " + KINDS.keySet());
        }
        String normalized = normalizeContentType(contentType, filename);
        if (!policy.contentTypes().contains(normalized)) {
            throw new BadRequestException(
                    "Content type '" + normalized + "' is not allowed for media kind '" + kind + "'");
        }
        if (sizeBytes >= 0 && sizeBytes > policy.maxUploadBytes()) {
            throw new BadRequestException(
                    "File exceeds the " + (policy.maxUploadBytes() / MB) + "MB limit for media kind '" + kind + "'");
        }
        return normalized;
    }

    private static String normalizeContentType(String contentType, String filename) {
        String ct = contentType == null ? "" : contentType.trim();
        int paramsIdx = ct.indexOf(';');
        if (paramsIdx >= 0) {
            ct = ct.substring(0, paramsIdx).trim();
        }
        ct = ct.toLowerCase(Locale.ROOT);
        if (ct.isEmpty() || ct.equals("application/octet-stream")) {
            String inferred = EXTENSION_TYPES.get(extensionOf(filename));
            if (inferred == null) {
                throw new BadRequestException(
                        "Cannot determine a content type for '" + filename + "' — upload with an explicit type");
            }
            return inferred;
        }
        return ct;
    }

    private static String extensionOf(String filename) {
        if (filename == null) {
            return "";
        }
        int dot = filename.lastIndexOf('.');
        return dot < 0 ? "" : filename.substring(dot + 1).toLowerCase(Locale.ROOT);
    }
}
