package com.bvisionry.courseaccess.domain;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Spec §3: "org-wide assignment is a RULE, not N rows". One row per
 * (organization, course); every current AND future member of that org is
 * covered, and unassignment is one delete.
 *
 * <p>Per-member opt-out is an {@code enrolment_overrides} row (V157) — the same
 * table the AI engine already consults, so one exclusion silences the rule AND
 * any future suggestion for that course. Member-level beats org-level.
 *
 * <p>Bare UUIDs, no JPA relations: org, course and user all live in other
 * slices.
 */
@Entity
@Table(name = "org_course_rules")
@Getter
@Setter
public class OrgCourseRule extends BaseEntity {

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Column(name = "course_id", nullable = false)
    private UUID courseId;

    /** Spec §3: required gates journey progress; optional is shown and never gates. */
    @Column(nullable = false)
    private boolean required;

    /** Optional, same field as the exercise dialog. Overdue shows in the journey. */
    @Column(name = "deadline")
    private Instant deadline;

    /** §7b: who first assigned it. Goes null after GDPR erasure; the row survives. */
    @Column(name = "created_by")
    private UUID createdBy;

    /** §7b: who last changed it. A re-assign never rewrites {@link #createdBy}. */
    @Column(name = "updated_by")
    private UUID updatedBy;
}
