package com.bvisionry.assessment.entity;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.publicassessment.entity.PublicAssessmentLink;
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
import java.util.UUID;

@Entity
@Table(name = "submissions")
@Getter
@Setter
@NoArgsConstructor
public class Submission extends BaseEntity {

    // Exactly one anchor is set (DB CHECK): assignment (+ user) for member
    // submissions, publicLink for anonymous public-assessment submissions.

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "assignment_id")
    private Assignment assignment;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "user_id")
    private User user;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "public_link_id")
    private PublicAssessmentLink publicLink;

    @Column(name = "respondent_email")
    private String respondentEmail;

    @Column(name = "respondent_name")
    private String respondentName;

    /**
     * Per-session secret minted at anonymous session start — the respondent's
     * credential for the public taker flow (same trust model as the survey
     * public token). Null for member submissions.
     */
    @Column(name = "access_token", unique = true)
    private UUID accessToken;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SubmissionStatus status = SubmissionStatus.IN_PROGRESS;

    @Column(name = "started_at", nullable = false)
    private Instant startedAt = Instant.now();

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "evaluated_at")
    private Instant evaluatedAt;

    @Column(name = "deadline_override")
    private Instant deadlineOverride;

    @Column(name = "failure_reason", columnDefinition = "TEXT")
    private String failureReason;

    public Instant getEffectiveDeadline() {
        if (deadlineOverride != null) return deadlineOverride;
        return assignment != null ? assignment.getDeadline() : null;
    }

    @Version
    private Long version;
}
