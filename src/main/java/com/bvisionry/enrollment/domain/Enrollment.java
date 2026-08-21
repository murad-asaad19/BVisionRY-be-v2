package com.bvisionry.enrollment.domain;

import com.bvisionry.common.enums.EnrollmentSource;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * Tracks a user's enrolment in a course.
 *
 * <p>Decoupled from the catalog slice: {@code courseId} is a plain UUID column
 * (no {@code @ManyToOne} to {@link com.bvisionry.catalog.domain.Course}) so the
 * enrollment slice can be deployed/tested independently.
 */
@Entity
@Table(name = "enrollment")
@Getter
@Setter
public class Enrollment {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private EnrollmentStatus status = EnrollmentStatus.ACTIVE;

    @Column(name = "progress_pct", nullable = false)
    private int progressPct = 0;

    @Column(name = "enrolled_at", nullable = false, updatable = false)
    private OffsetDateTime enrolledAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    /**
     * WHY the member has this course (spec §3, V168). Defaults to SELF — the
     * only path that existed before an admin or a rule could put someone on a
     * course, and the weakest claim, so a mis-stamped row under-states rather
     * than over-states.
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "source", nullable = false, length = 20)
    private EnrollmentSource source = EnrollmentSource.SELF;

    /** §7b: who assigned it. Null for SELF and for the AI engine. */
    @Column(name = "assigned_by")
    private UUID assignedBy;

    /** Spec §3: optional, same field as the exercise dialog. Overdue shows in the journey. */
    @Column(name = "deadline")
    private OffsetDateTime deadline;

    /** Spec §3/§11: required gates journey progress, and is mutable after assignment. */
    @Column(name = "required", nullable = false)
    private boolean required = false;

    @OneToMany(mappedBy = "enrollment", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<ContentProgress> contentProgresses = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (enrolledAt == null) {
            enrolledAt = OffsetDateTime.now();
        }
    }

    @PreUpdate
    void onUpdate() {
        // completedAt is managed by the service layer.
    }
}
