package com.bvisionry.pipeline.entity;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One decision the auto-enrolment engine made: "this founder, this course, this
 * evaluation — here is what happened and which pillar asked for it"
 * (roadmap §7 item 10).
 *
 * <p>This row does three jobs, which is why there is one table and not three:
 * <ol>
 *   <li><strong>The idempotency key.</strong> {@code (userId, courseId,
 *       submissionId)} is unique — exactly {@code auto_enrolment_idempotency_key}
 *       from agent-policy. Re-processing the same evaluation finds its own rows and
 *       does nothing; a NEW evaluation has a different {@code submissionId} and may
 *       add courses the founder does not have yet.</li>
 *   <li><strong>The WHY.</strong> {@code pillarId} + {@code bandPosition} are what
 *       {@code dashboard_recommendations} reads to say "recommended because of
 *       &lt;pillar&gt;". Stored, never re-derived: the founder's bands may be
 *       re-labelled long after they were measured.</li>
 *   <li><strong>The audit trail.</strong> {@code outcome} distinguishes a write, an
 *       idempotent no-op and a refusal, so "why wasn't X enrolled" has an answer
 *       without a second audit framework.</li>
 * </ol>
 *
 * <p>Every id is a plain {@code UUID} column, not a {@code @ManyToOne} — the same
 * decoupling {@code enrollment.Enrollment} and {@link PillarCourseMapping} use, and
 * the reason the pipeline package imports no auth, assessment or catalog type. The
 * FKs and their cascades live in the schema (V151).
 *
 * <p><strong>No org column, deliberately.</strong> An enrolment is (user, course)
 * and nothing else — the catalog is a public cross-org surface ("the public catalog
 * returns ALL PUBLISHED courses regardless of which org created them",
 * {@code CourseRepository}), so a founder of one org legitimately learns a course
 * another org wrote. Attribution is {@code userId}; the org is whatever the user's
 * membership says at read time, exactly as {@code enrollment} has always modelled it.
 */
@Entity
@Table(name = "auto_enrolments")
@Getter
@Setter
@NoArgsConstructor
public class AutoEnrolment extends BaseEntity {

    /** The founder. FK to {@code users.id}. */
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** FK to {@code course.id}. */
    @Column(name = "course_id", nullable = false, updatable = false)
    private UUID courseId;

    /** The source evaluation. FK to {@code submissions.id}. */
    @Column(name = "submission_id", nullable = false, updatable = false)
    private UUID submissionId;

    /** The pillar that triggered it — the "because of" half of the reason. */
    @Column(name = "pillar_id", nullable = false, updatable = false)
    private UUID pillarId;

    /** The band position the founder's STORED label resolved to (RULING 4). */
    @Column(name = "band_position", nullable = false, updatable = false)
    private int bandPosition;

    @Enumerated(EnumType.STRING)
    @Column(name = "outcome", nullable = false, length = 32)
    private AutoEnrolmentOutcome outcome;

    /**
     * When the founder took a {@link AutoEnrolmentOutcome#SUGGESTED} row up
     * (spec §7b). Null on every other outcome, and on a suggestion still open —
     * which is exactly the predicate the recommendation reads use.
     */
    @Column(name = "accepted_at")
    private java.time.Instant acceptedAt;
}
