package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;

import com.bvisionry.programflow.domain.ModuleLockMode;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record UpdateModuleRequest(
        @NotBlank @Size(max = 200) String name,
        String summary,
        @NotNull ModuleLockMode lockMode,
        OffsetDateTime unlockAt) {
}
