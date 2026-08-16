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

    /**
     * §7b history, write-once: the FIRST (well, latest) time a reviewer sent
     * this copy back. Never cleared by a resubmit or a later mark-reviewed —
     * unlike {@link #reviewedAt}, which both of those null — so the review
     * queue can honestly mark a SUBMITTED copy as "resubmitted after changes".
     */
    @Column(name = "changes_requested_at")
    private Instant changesRequestedAt;

    /**
     * Reviewer's quality tag (spec §4/§11), V170 — <strong>metadata only</strong>,
     * never an input to the participation score. {@code key} is the §7 stable
     * identity; {@code label} is the snapshot taken at tagging time so a later
     * rename or deletion of the tag never rewrites what the reviewer said.
     * All four are re-stamped on a re-tag and nulled together on a clear.
     */
    @Column(name = "quality_tag_key")
    private String qualityTagKey;

    @Column(name = "quality_tag_label")
    private String qualityTagLabel;

    @Column(name = "quality_tagged_at")
    private Instant qualityTaggedAt;

    /** Bare id, not a relation: no JPA edges across features. */
    @Column(name = "quality_tagged_by")
    private java.util.UUID qualityTaggedBy;

    @Version
    private Long version;
}
