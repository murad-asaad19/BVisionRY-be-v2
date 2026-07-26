package com.bvisionry.pipeline.entity;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One admin-declared rule: "a founder landing in band {@code bandPosition} of
 * this pillar should be pointed at this course" (roadmap §7 item 9).
 *
 * <p>{@code bandPosition} is the 0-based ORDINAL POSITION of the band within
 * the pillar's own {@code maturityThresholds}, ordered lowest to highest by
 * minimum score — never a band name. Bands are per-pillar configurable data
 * with bespoke per-customer vocabularies (agent-decisions RULING 4), so a name
 * is not an identity that survives another pillar, and position 0 is always the
 * weakest band.
 *
 * <p>{@code courseId} is a plain {@code UUID}, not a {@code @ManyToOne} — the
 * same decoupling {@code enrollment.Enrollment} uses, and the reason the
 * pipeline package imports no catalog type. The FK and its cascade live in the
 * schema (V150).
 */
@Entity
@Table(name = "pillar_course_mappings")
@Getter
@Setter
@NoArgsConstructor
public class PillarCourseMapping extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pillar_id", nullable = false)
    private Pillar pillar;

    @Column(name = "band_position", nullable = false)
    private int bandPosition;

    /** FK to {@code course.id} — stored as a plain UUID (no {@code @ManyToOne}). */
    @Column(name = "course_id", nullable = false)
    private UUID courseId;
}
