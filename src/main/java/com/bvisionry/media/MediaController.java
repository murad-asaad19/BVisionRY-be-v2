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
@Tag(name = "Media", description = "Lesson media upload (SUPER_ADMIN / INSTRUCTOR).")
public class MediaController {

    private final MediaService mediaService;

    public MediaController(MediaService mediaService) {
        this.mediaService = mediaService;
    }

    @Operation(summary = "Upload lesson media to MinIO",
               description = "Stores the file in the configured MinIO bucket and returns a "
                           + "persistent minio:// marker plus an immediately usable presigned URL.")
    @PostMapping(value = "/media", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @ResponseStatus(HttpStatus.CREATED)
    public MediaUploadResponse upload(
            @RequestParam("file") MultipartFile file,
            @RequestParam(name = "kind", defaultValue = "asset") String kind) {

        String marker     = mediaService.upload(file, kind);
        String previewUrl = mediaService.resolveUrl(marker);
        return new MediaUploadResponse(marker, previewUrl);
    }

    @Operation(summary = "Presign a direct-to-MinIO upload",
               description = "Returns a short-lived presigned PUT URL the browser uploads the file to "
                           + "directly — bypassing application-server / proxy body-size limits (e.g. "
                           + "Vercel Functions' 4.5MB cap) — plus the persistent minio:// marker and a "
                           + "presigned GET preview URL. Use this instead of POST /media for large files.")
    @PostMapping(value = "/media/presign", consumes = MediaType.APPLICATION_JSON_VALUE)
    public MediaService.PresignedUpload presignUpload(@RequestBody PresignUploadRequest request) {
        return mediaService.presignUpload(request.kind(), request.filename(), request.contentType());
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
     */
    public record PresignUploadRequest(String filename, String contentType, String kind) {}

    /**
     * Returned by {@code POST /api/v1/media}.
     *
     * @param marker     the {@code minio://bucket/objectKey} value to persist in the DB
     * @param previewUrl a presigned GET URL valid for {@code bvisionry.minio.presigned-expiry-minutes}
     */
    public record MediaUploadResponse(String marker, String previewUrl) {}
}
