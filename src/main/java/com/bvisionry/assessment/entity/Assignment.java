package com.bvisionry.assessment.entity;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pipeline;
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
 * A pipeline assigned within an organization. When {@link #user} is null the row
 * is an org-level provision (super admin assigned the pipeline to the org; org
 * admin distributes to members). When {@link #user} is set, the row is one
 * pipeline×member assignment — bulk "assign all" creates N such rows.
 */
@Entity
@Table(name = "assignments")
@Getter
@Setter
@NoArgsConstructor
public class Assignment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "organization_id", nullable = false)
    private Organization organization;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @Column(name = "assigned_by", nullable = false)
    private UUID assignedBy;

    private Instant deadline;

    /**
     * @see com.bvisionry.assessment.dto.CreateAssignmentRequest#maxCheckIns
     */
    @Column(name = "max_check_ins", nullable = false)
    private int maxCheckIns = 1;
}
