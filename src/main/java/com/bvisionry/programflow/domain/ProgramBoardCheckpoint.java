package com.bvisionry.programflow.domain;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One admin's board safety net: the whole curriculum (modules + audience +
 * tasks + fields) as it looked when they opened the cohort's board. One row per
 * (cohort, admin) — re-opening the board overwrites it, so {@code createdAt} is
 * set by the service on every capture rather than being a create-only stamp.
 */
@Entity
@Table(name = "program_board_checkpoints")
@Getter
@Setter
public class ProgramBoardCheckpoint {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    /** {@link BoardSnapshot} as plain JSON values. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "created_at", nullable = false)
    private OffsetDateTime createdAt;
}
