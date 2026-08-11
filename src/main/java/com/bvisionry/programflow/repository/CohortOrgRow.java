package com.bvisionry.programflow.repository;

import java.time.Instant;
import java.util.UUID;

/** Native-query projection of one cohort↔org assignment with display fields. */
public interface CohortOrgRow {

    UUID getOrgId();

    String getOrgName();

    /** Parent org's name; null for a root org. */
    String getParentName();

    boolean getAutoEnroll();

    Instant getAssignedAt();

    long getEnrolledCount();
}
