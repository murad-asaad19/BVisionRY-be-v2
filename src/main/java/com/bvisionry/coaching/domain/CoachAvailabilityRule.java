package com.bvisionry.coaching.domain;

import java.time.LocalTime;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One recurring weekly window a coach is bookable in (V215, spec §2.1). The
 * times are WALL-CLOCK in {@link CoachProfile#getTimeZone()} — never instants —
 * so "Mondays 09:00" stays 09:00 across a DST change; turning them into
 * instants is {@code SlotEngine}'s job.
 *
 * <p>Soft-coupled to identity by UUID like {@link CoachAssignment}: the FK
 * exists at the DB level, the Java slice never imports {@code auth}.
 */
@Entity
@Table(name = "coach_availability_rules")
@Getter
@Setter
public class CoachAvailabilityRule {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    /** ISO weekday: 1 = Monday … 7 = Sunday, matching {@link java.time.DayOfWeek#getValue()}. */
    @Column(name = "weekday", nullable = false)
    private Short weekday;

    @Column(name = "start_time", nullable = false)
    private LocalTime startTime;

    @Column(name = "end_time", nullable = false)
    private LocalTime endTime;
}
