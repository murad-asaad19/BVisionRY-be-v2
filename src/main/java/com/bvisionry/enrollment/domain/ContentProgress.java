package com.bvisionry.enrollment.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Tracks per-lesson completion within an {@link Enrollment}.
 */
@Entity
@Table(name = "content_progress")
@Getter
@Setter
public class ContentProgress {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "enrollment_id", nullable = false)
    private Enrollment enrollment;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "completed", nullable = false)
    private boolean completed = false;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /** Last known playback position in seconds (V83). */
    @Column(name = "last_position_seconds", nullable = false)
    private int lastPositionSeconds = 0;

    /** Percentage of the video watched, 0–100 (V83). */
    @Column(name = "watched_pct", nullable = false)
    private int watchedPct = 0;

    @PrePersist
    void onCreate() {
        // completed_at is set by the service when marking complete.
    }
}
