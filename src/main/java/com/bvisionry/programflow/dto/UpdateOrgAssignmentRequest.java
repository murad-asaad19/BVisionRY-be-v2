package com.bvisionry.programflow.dto;

/** Edits an org assignment's enrollment rule. Not retroactive. */
public record UpdateOrgAssignmentRequest(boolean autoEnroll) {
}
