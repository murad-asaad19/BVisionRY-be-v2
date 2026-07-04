package com.bvisionry.programflow.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Per-cohort program tweakables. Row is optional — absence means defaults
 * ({@code Week}, drip on, due-soon 3 days, no program-end flag).
 */
@Entity
@Table(name = "program_settings")
@Getter
@Setter
public class ProgramSettings {

    @Id
    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    @Column(name = "stage_label", nullable = false, length = 30)
    private String stageLabel = "Week";

    @Column(name = "drip_enabled", nullable = false)
    private boolean dripEnabled = true;

    @Column(name = "due_soon_days", nullable = false)
    private int dueSoonDays = 3;

    @Column(name = "end_label", length = 120)
    private String endLabel;

    @Column(name = "end_at")
    private OffsetDateTime endAt;
}
