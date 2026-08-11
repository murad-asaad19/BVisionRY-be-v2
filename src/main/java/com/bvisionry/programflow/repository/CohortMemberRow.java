package com.bvisionry.programflow.repository;

import java.util.UUID;

/**
 * Native-query projection of an enrolled cohort founder with their org —
 * the roster primitive of the platform-cohort model (spec §13). Keeps the
 * programflow slice free of any Java dependency on the {@code auth} feature.
 */
public interface CohortMemberRow {

    UUID getId();

    String getName();

    String getEmail();

    UUID getOrgId();

    String getOrgName();
}
