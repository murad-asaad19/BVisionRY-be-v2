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
 * A coach's own profile row (V153, widened by V178): who the coach IS, plus the
 * Cal.com booking page they already own, which the founder's browser opens
 * directly (policy {@code calendar: INTEGRATE_CAL_COM} — we never build
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

    /** V178: one line under the name on Coaches Corner ("Fundraising & GTM"). */
    @Column(name = "headline", length = 160)
    private String headline;

    /**
     * V178: a serialised tiptap document (same shape as
     * {@code exercise_templates.description}), rendered by {@code RichTextView}.
     */
    @Column(name = "bio")
    private String bio;

    /**
     * V178: a {@code minio://bucket/key} marker or an external URL. NEVER handed
     * to a browser raw — the founder-facing read resolves it through
     * {@link com.bvisionry.common.media.MediaUrlPort}.
     */
    @Column(name = "photo_url", length = 500)
    private String photoUrl;

    /**
     * V215 (spec §2.1): the IANA zone the coach's weekly availability windows
     * are expressed in. Null until they save availability for the first time —
     * the windows are wall-clock, so without this the slot engine cannot turn
     * them into instants and the coach is simply not bookable yet.
     */
    @Column(name = "time_zone", length = 64)
    private String timeZone;

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
