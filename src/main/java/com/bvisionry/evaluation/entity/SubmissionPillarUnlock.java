package com.bvisionry.evaluation.entity;

import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.pipeline.entity.Pillar;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import jakarta.persistence.UniqueConstraint;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Active record of a pillar that an admin has temporarily unlocked on an
 * already-EVALUATED submission so the member can re-edit their answers.
 *
 * <p>Rows live for the duration of the unlock window — they are inserted when
 * the admin unlocks (transitioning the submission EVALUATED → PENDING_REEDIT)
 * and deleted when either the admin re-locks or the member re-submits and the
 * partial re-evaluation completes successfully.
 */
@Entity
@Table(
        name = "submission_pillar_unlocks",
        uniqueConstraints = @UniqueConstraint(
                name = "uq_submission_pillar_unlock",
                columnNames = {"submission_id", "pillar_id"})
)
@Getter
@Setter
@NoArgsConstructor
public class SubmissionPillarUnlock extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pillar_id", nullable = false)
    private Pillar pillar;

    @Column(name = "unlocked_at", nullable = false)
    private Instant unlockedAt = Instant.now();

    @Column(name = "unlocked_by_admin_id", nullable = false)
    private UUID unlockedByAdminId;

    @Column(name = "reason", columnDefinition = "TEXT")
    private String reason;
}
