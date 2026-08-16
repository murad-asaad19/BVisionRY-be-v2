package com.bvisionry.communication.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * Body of {@code POST .../cohorts/{cohortId}/announcements}. Plain text only
 * (policy {@code announcement_body: PLAIN_TEXT_PLUS_LINKS}) — a body carrying
 * markup or HTML codes is rejected rather than rewritten, so what is stored is
 * exactly what the author typed.
 *
 * <p>The 500-character ceiling is the DELIVERY surface's, not the column's: an
 * announcement is read in the notification bell and shipped as a web-push
 * payload, and browsers cap that payload around 4 KB after encryption. A cap
 * the recipient's surface can actually honour beats a generous one it silently
 * clips. The composer shows a counter as an author approaches it.
 */
public record CreateAnnouncementRequest(
        @NotBlank(message = "Write something to announce.")
        @Size(max = 500, message = "Keep an announcement under 500 characters.")
        String body) {
}
