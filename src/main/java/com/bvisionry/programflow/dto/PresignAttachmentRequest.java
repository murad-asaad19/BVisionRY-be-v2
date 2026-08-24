package com.bvisionry.programflow.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

/**
 * Body of {@code POST /api/my/program/tasks/{taskId}/attachments/presign}.
 *
 * @param filename    original filename (used to build a readable object key and
 *                    for the content-type extension fallback)
 * @param contentType the file's MIME type; blank falls back to the extension
 * @param sizeBytes   declared upload size — required: it is checked against the
 *                    org's storage quota and signed into the PUT as
 *                    Content-Length, so the body must be exactly this long
 */
public record PresignAttachmentRequest(
        @NotBlank String filename,
        String contentType,
        @NotNull @Positive Long sizeBytes) {}
