package com.bvisionry.coaching.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A coach's own profile row (V153). Today it holds exactly one thing: the
 * Cal.com booking page the coach already owns, which the founder's browser
 * opens directly (policy {@code calendar: INTEGRATE_CAL_COM} — we never build
 * booking, and we store nothing about bookings).
 *
 * <p>The PK IS the coach's {@code users(id)}, so "may I write this row?" has
 * exactly one answer: only when the id equals the authenticated principal.
 * There is deliberately no surrogate key and no {@code orgId} — a request can
 * name neither, so neither can be tampered with.
 *
 * <p>Soft-coupled to identity by UUID (the {@link CoachAssignment} convention):
 * the FK exists at the DB level, the Java slice never imports {@code auth}.
 */
@Entity
@Table(name = "coach_profiles")
@Getter
@Setter
public class CoachProfile {

    @Id
    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    /** Null or absent = the coach has published no booking link. */
    @Column(name = "booking_url")
    private String bookingUrl;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
