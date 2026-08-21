package com.bvisionry.programflow.repository;

import java.util.UUID;

/** One cohort→org assignment as a display label ("Parent → Sub"), for the switcher. */
public interface CohortOrgNameRow {

    UUID getCohortId();

    String getOrgName();
}
