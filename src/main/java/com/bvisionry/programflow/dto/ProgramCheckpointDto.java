package com.bvisionry.programflow.dto;

import java.time.OffsetDateTime;

/**
 * The caller's board checkpoint: when their current editing session began — the
 * point "Revert changes" restores the curriculum to.
 */
public record ProgramCheckpointDto(OffsetDateTime createdAt) {
}
