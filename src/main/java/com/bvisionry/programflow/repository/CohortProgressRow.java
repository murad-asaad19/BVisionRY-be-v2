package com.bvisionry.programflow.repository;

import java.util.UUID;

/** One cohort's module count + stage label, for the org cohort-card progress bar (spec §8). */
public interface CohortProgressRow {

    UUID getCohortId();

    int getModuleCount();

    String getStageLabel();
}
