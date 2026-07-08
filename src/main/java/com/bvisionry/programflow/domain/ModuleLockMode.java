package com.bvisionry.programflow.domain;

/** Drip-locking mode of a {@link ProgramModule}. */
public enum ModuleLockMode {
    /** Always open. */
    UNLOCKED,
    /** Opens when the previous module is fully submitted by the learner. */
    SEQUENTIAL,
    /** Opens at a fixed date/time ({@code unlockAt}). */
    SCHEDULED
}
