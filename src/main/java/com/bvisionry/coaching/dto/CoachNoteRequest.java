package com.bvisionry.coaching.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** Create/update body for a coach note. */
public record CoachNoteRequest(
        @NotBlank @Size(max = 8000) String body) {
}
