package com.bvisionry.programflow.repository;

import java.time.OffsetDateTime;
import java.util.UUID;

/** Native-query projection of one cohort↔org assignment with display fields. */
public interface CohortOrgRow {

    UUID getOrgId();

    String getOrgName();

    boolean getAutoEnroll();

    OffsetDateTime getAssignedAt();

    long getEnrolledCount();
}
