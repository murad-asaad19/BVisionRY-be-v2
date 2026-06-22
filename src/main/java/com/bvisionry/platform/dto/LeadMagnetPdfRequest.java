package com.bvisionry.platform.dto;

import jakarta.validation.constraints.Size;

/**
 * Request body for PUT /api/admin/settings/lead-magnet.
 *
 * <p>{@code marker} is the value returned by the media upload endpoint
 * ({@code minio://bucket/key}) or an external URL pasted in the dropzone.
 * A {@code null}/blank marker clears the configured PDF.
 */
public record LeadMagnetPdfRequest(
        @Size(max = 1024, message = "Marker must be at most 1024 characters")
        String marker
) {
}
