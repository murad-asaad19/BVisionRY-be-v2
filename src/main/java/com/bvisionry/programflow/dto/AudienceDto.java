package com.bvisionry.programflow.dto;

import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.AudienceMode;

/** Who sees a module, plus how many enrolled founders that currently reaches. */
public record AudienceDto(
        AudienceMode mode,
        List<UUID> memberIds,
        int reached) {
}
