package com.bvisionry.coaching.domain;

import java.time.OffsetDateTime;
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
 * Time off / a one-off blackout punched through the weekly windows (V215, spec
 * §2.1). Unlike {@link CoachAvailabilityRule} these ARE instants: a holiday is
 * a fixed span of real time, not a wall-clock pattern.
 */
@Entity
@Table(name = "coach_availability_blocks")
@Getter
@Setter
public class CoachAvailabilityBlock {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    @Column(name = "starts_at", nullable = false)
    private OffsetDateTime startsAt;

    @Column(name = "ends_at", nullable = false)
    private OffsetDateTime endsAt;

    @Column(name = "reason", length = 200)
    private String reason;
}
