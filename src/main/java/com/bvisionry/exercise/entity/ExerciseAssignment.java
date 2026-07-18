package com.bvisionry.exercise.entity;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.organization.entity.Organization;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An exercise template assigned within an organization. Same dual-role model
 * as {@link com.bvisionry.assessment.entity.Assignment}: when {@link #user} is
 * null the row is the org-level provision (super admin granted the template to
 * the org); when set, it is one template×member assignment.
 */
@Entity
@Table(name = "exercise_assignments")
@Getter
@Setter
@NoArgsConstructor
public class ExerciseAssignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private ExerciseTemplate template;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "assigned_by", nullable = false)
    private UUID assignedBy;

    private Instant deadline;
}
