package com.bvisionry.platform.dto;

/**
 * Current lead-magnet PDF configuration for the admin settings card.
 *
 * @param marker     the stored {@code minio://} marker or external URL ({@code null} when unset)
 * @param fileName   a display/download file name derived from the marker ({@code null} when unset)
 * @param previewUrl a browser-loadable URL (presigned for minio markers) for the admin preview link
 */
public record LeadMagnetPdfResponse(String marker, String fileName, String previewUrl) {
}
