package com.bvisionry.catalog.dto.authoring;

import java.util.UUID;

import com.bvisionry.common.validation.ValidExternalUrl;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Request body for POST/PUT on a content (lesson) item.
 */
public record UpsertContentRequest(
        @NotBlank @Size(max = 200) String title,
        @NotBlank String contentType,
        int sequence,
        Integer durationMin,
        boolean allowPreview,
        /** Tiptap JSON document for PAGE content. */
        String body,
        /** HLS / direct video URL for VIDEO content. */
        // Constrain to http(s) public URLs: a raw String would let an author store
        // assetUrl="javascript:..." (stored XSS) or internal schemes (SSRF). Null/blank pass through.
        @ValidExternalUrl String videoUrl,
        /** PDF or other asset URL. */
        @ValidExternalUrl String assetUrl,
        /** For ASSIGNMENT lessons: the embedded FRI pipeline id. Nullable. */
        UUID pipelineId) {
}
