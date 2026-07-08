package com.bvisionry.evaluation.entity;

import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.OneToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * The stored result of the on-demand AI-use detector for one submission —
 * overwritten in place on each admin re-run ({@code updatedAt} is the "detected
 * at" timestamp). The verdict band is derived from the score at read time.
 */
@Entity
@Table(name = "submission_ai_detections")
@Getter
@Setter
@NoArgsConstructor
public class SubmissionAiDetection extends BaseEntity {

    @OneToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false, unique = true)
    private Submission submission;

    @Column(name = "ai_likelihood_score", nullable = false)
    private int aiLikelihoodScore;

    /** JSON array of {qid, questionText, note}, resolved at detection time. */
    @Column(name = "answer_findings", columnDefinition = "TEXT")
    private String answerFindings;

    @Column(nullable = false)
    private String model;

    @Column(name = "prompt_version_id")
    private UUID promptVersionId;
}
