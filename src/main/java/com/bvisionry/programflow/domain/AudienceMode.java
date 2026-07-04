package com.bvisionry.programflow.domain;

/** Who sees a {@link ProgramModule} on their journey. */
public enum AudienceMode {
    /** Everyone in the organization. */
    ALL,
    /** Members of the selected teams. */
    TEAMS,
    /** Hand-picked members. */
    MEMBERS
}
