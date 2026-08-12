package com.bvisionry.programflow.dto;

/**
 * Board revert. {@code force} confirms deleting the member work that hangs off
 * tasks added since the checkpoint — the plain revert refuses with a 409 that
 * names what would be lost instead.
 */
public record RevertCheckpointRequest(boolean force) {
}
