package com.bvisionry.programflow.repository;

import java.util.UUID;

/** Projection: a sub-organization with its parent name and learner + cohort + workshop counts. */
public interface OrgProgramRow {

    UUID getId();

    String getName();

    String getDescription();

    String getParentName();

    long getMemberCount();

    long getCohortCount();

    long getWorkshopCount();

    /** Listed on the Program Flow console — independent of whether it has cohorts. */
    boolean getInProgramFlow();

    /** Listed on the Workshops console — independent of whether it has workshops. */
    boolean getInWorkshops();
}
