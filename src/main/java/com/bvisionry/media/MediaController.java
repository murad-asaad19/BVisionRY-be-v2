package com.bvisionry.media;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;

/**
 * REST endpoint for lesson-media uploads.
 *
 * <pre>
 *   POST /api/v1/media   multipart/form-data
 *     file — the binary payload
 *     kind — optional prefix / folder (default: "asset")
 * </pre>
 *
 * Returns {@link MediaUploadResponse} containing:
 * <ul>
 *   <li>{@code marker}    — the {@code minio://bucket/key} string to persist in
 *                           {@code content.video_url} or {@code content.asset_url}.</li>
 *   <li>{@code previewUrl} — a fresh presigned GET URL the browser can use immediately.</li>
 * </ul>
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAnyAuthority('SUPER_ADMIN', 'INSTRUCTOR')")
@Tag(name = "Media", description = "Lesson media upload (SUPER_ADMIN / INSTRUCTOR), plus org-scoped "
                                 + "branding-logo upload (ORG_ADMIN, images only).")
public class MediaController {

    /**
     * The one authorization expression both upload paths carry.
     *
     * <p>Split on {@code orgId} rather than on role, and that split is the
     * point. The two branches are MUTUALLY EXCLUSIVE:
     *
     * <ul>
     *   <li><strong>No {@code orgId}</strong> — the historical platform path
     *       (lesson media), SUPER_ADMIN / INSTRUCTOR, unchanged.</li>
     *   <li><strong>An {@code orgId}</strong> — the org-scoped branding path,
     *       and EVERY caller on it (SUPER_ADMIN included) must pass
     *       {@code @orgAccess.isInOrg}, the same tenancy predicate every
     *       {@code /api/organizations/{id}/*} surface uses. It grants a
     *       parent-org admin access to sub-orgs they govern and nothing
     *       else.</li>
     * </ul>
     *
     * <p>WHY NOT the obvious
     * {@code hasAnyAuthority('SUPER_ADMIN','INSTRUCTOR') or (ORG_ADMIN and isInOrg)}:
     * INSTRUCTOR is an ORG-SCOPED role here — an ORG_ADMIN can invite one
     * ({@code InvitationService}), and {@code QuizService} already carries the
     * warning that a bare role check is org-agnostic. That spelling
     * short-circuits on the role before the tenancy predicate is ever
     * evaluated, so an instructor in org A could POST with
     * {@code orgId=<orgB>} and write into another tenant's branding namespace,
     * receiving a valid marker and a presigned URL for it. That falsifies the
     * exact invariant the branding IDOR guard rests on.
     *
     * <p>Requiring {@code orgId} for the org path is also what makes the kind
     * restriction structural: {@code MediaUploadPolicy.requireOrgScopedKindIsImage}
     * refuses any upload carrying an {@code orgId} whose kind is not
     * {@code image}, so the only upload an ORG_ADMIN can perform at all is an
     * image into an org they govern. Both halves are enforced on BOTH paths.
     */
    private static final String UPLOAD_AUTHORIZATION =
            // COACH is admitted ONLY on the org-less path, and MediaUploadPolicy
            // .requireCoachKindIsImage bounds them to kind=image there — the same
            // two-halves shape that admits ORG_ADMIN for branding images.
            "(#orgId == null and hasAnyAuthority('SUPER_ADMIN', 'INSTRUCTOR', 'COACH')) "
          + "or (#orgId != null and hasAnyAuthority('SUPER_ADMIN', 'ORG_ADMIN') "
          + "and @orgAccess.isInOrg(#orgId))";

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Operation(summary = "Upload lesson media to MinIO",
               description = "Stores the file in the configured MinIO bucket and returns a "
                           + "persistent minio:// marker plus an immediately usable presigned URL. "
                           + "Pass orgId to store a white-label branding image under that org's "
                           + "own key prefix (kind must be 'image').")
    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    @PreAuthorize(UPLOAD_AUTHORIZATION)
    public MediaUploadResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "kind", defaultValue = "asset") String kind,
            @RequestParam(name = "orgId", required = false) UUID orgId) {

        String marker     = mediaService.upload(file, kind, orgId);
        String previewUrl = mediaService.resolveUrl(marker);
        return new MediaUploadResponse(marker, previewUrl);
    }

    @Operation(summary = "Presign a direct-to-MinIO upload",
               description = "Returns a short-lived presigned PUT URL the browser uploads the file to "
                           + "directly — bypassing application-server / proxy body-size limits (e.g. "
                           + "Vercel Functions' 4.5MB cap) — plus the persistent minio:// marker and a "
                           + "presigned GET preview URL. Use this instead of POST /media for large files. "
                           + "Pass orgId for a white-label branding image (kind must be 'image'); that "
                           + "variant REQUIRES sizeBytes and binds BOTH Content-Type and Content-Length "
                           + "into the signature, so the PUT must send the returned contentType verbatim "
                           + "and a body of exactly sizeBytes bytes — the org's storage quota is checked "
                           + "against that declaration before the URL is issued, and the signature is what "
                           + "makes the declaration binding.")
    @PostMapping(value = "/media/presign", consumes = MediaType.APPLICATION_JSON_VALUE)
    @PreAuthorize(UPLOAD_AUTHORIZATION)
    public MediaService.PresignedUpload presignUpload(
            @RequestBody PresignUploadRequest request,
            @RequestParam(name = "orgId", required = false) UUID orgId) {
        // orgId rides as a QUERY parameter, not a body field, precisely so the
        // @PreAuthorize expression above can read it: SpEL cannot be relied on
        // to reach a record component, and an authorization rule that silently
        // evaluates `null` for a mistyped property reference fails OPEN.
        return mediaService.presignUpload(
                request.kind(), request.filename(), request.contentType(), request.sizeBytes(), orgId);
    }

    // -------------------------------------------------------------------------
    // Request / Response DTOs
    // -------------------------------------------------------------------------

    /**
     * Body of {@code POST /api/v1/media/presign}.
     *
     * @param filename    original filename (used only to build a readable object key)
     * @param contentType the file's MIME type (the browser applies it to the object on PUT)
     * @param kind        folder prefix (e.g. {@code "pdf"}); defaults to {@code "asset"} when blank
     * @param sizeBytes   declared upload size; REQUIRED when {@code orgId} is passed (org-scoped
     *                    storage quota is checked against it before the URL is issued), unused
     *                    otherwise. On the org-scoped path it is signed into the URL as
     *                    {@code Content-Length}, so the PUT body must be exactly this long —
     *                    see {@code MediaService#presignUpload} and {@code OrgStorageQuotaService}.
     */
    public record PresignUploadRequest(String filename, String contentType, String kind, Long sizeBytes) {}

    /**
     * Returned by {@code POST /api/v1/media}.
     *
     * @param marker     the {@code minio://bucket/objectKey} value to persist in the DB
     * @param previewUrl a presigned GET URL valid for {@code bvisionry.minio.presigned-expiry-minutes}
     */
    public record MediaUploadResponse(String marker, String previewUrl) {}
}
