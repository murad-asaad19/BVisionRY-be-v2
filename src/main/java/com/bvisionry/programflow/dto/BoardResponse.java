package com.bvisionry.programflow.dto;

import java.util.List;

/** Everything the admin program board renders in one call. */
public record BoardResponse(
        ProgramSettingsDto settings,
        List<ModuleDto> modules,
        BoardStats stats) {

    public record BoardStats(int modules, int tasks, int members) {
    }
}
