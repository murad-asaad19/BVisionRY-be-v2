package com.bvisionry.programflow.repository;

import java.util.UUID;

/** Projection: an organization with its learner + cohort counts (org picker/switcher). */
public interface OrgProgramRow {

    UUID getId();

    String getName();

    String getDescription();

    long getMemberCount();

    long getCohortCount();
}
