package com.bvisionry.programflow.dto;

import java.util.List;

/**
 * Everything the admin program board renders in one call.
 *
 * <p>{@code version} is the curriculum's version at this read. The builder holds
 * it for the duration of an editing session and hands it back on Save; see
 * {@link SaveBoardRequest#expectedVersion()}.
 */
public record BoardResponse(
        ProgramSettingsDto settings,
        List<ModuleDto> modules,
        long version,
        BoardStats stats) {

    public record BoardStats(int modules, int tasks, int members) {
    }
}
