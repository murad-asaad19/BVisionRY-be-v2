package com.bvisionry.programflow.dto;

import java.util.List;
import java.util.UUID;

import com.bvisionry.programflow.domain.AudienceMode;

/** Who sees a module, plus how many org members that currently reaches. */
public record AudienceDto(
        AudienceMode mode,
        List<UUID> teamIds,
        List<UUID> memberIds,
        int reached) {
}
