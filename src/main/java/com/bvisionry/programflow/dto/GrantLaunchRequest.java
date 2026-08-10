package com.bvisionry.programflow.dto;

import jakarta.validation.constraints.Size;

/** SUPER_ADMIN grant-extra-launch override (spec §8); the note is optional. */
public record GrantLaunchRequest(
        @Size(max = 500) String note) {
}
