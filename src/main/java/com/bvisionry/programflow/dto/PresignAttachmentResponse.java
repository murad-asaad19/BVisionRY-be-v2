package com.bvisionry.programflow.dto;

/**
 * Returned by {@code POST /api/my/program/tasks/{taskId}/attachments/presign}.
 *
 * @param uploadUrl   short-lived presigned PUT URL to upload the bytes to directly
 * @param marker      the {@code minio://bucket/objectKey} value to store as the
 *                    FILE answer's {@code url}
 * @param previewUrl  a presigned GET URL usable once the PUT completes
 * @param contentType the normalized content type the signature was computed
 *                    with — the PUT must send exactly this Content-Type header
 */
public record PresignAttachmentResponse(
        String uploadUrl, String marker, String previewUrl, String contentType) {}
