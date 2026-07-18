package com.bvisionry.exercise.entity;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.Version;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;

/**
 * A member's working copy of an exercise — one per member assignment, created
 * at assignment time. Rows stay editable in every status; see
 * {@link ExerciseSubmissionStatus} for the review handshake.
 */
@Entity
@Table(name = "exercise_submissions")
@Getter
@Setter
@NoArgsConstructor
public class ExerciseSubmission extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id", nullable = false)
    private ExerciseAssignment assignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id", nullable = false)
    private User user;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExerciseSubmissionStatus status = ExerciseSubmissionStatus.IN_PROGRESS;

    @Column(name = "last_saved_at")
    private Instant lastSavedAt;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "reviewed_at")
    private Instant reviewedAt;

    @Version
    private Long version;
}
