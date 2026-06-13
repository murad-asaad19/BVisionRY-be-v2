package com.bvisionry.publicassessment.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.survey.entity.RespondentFieldMode;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * Admin-published QR/link entry point that lets anonymous respondents take a
 * PUBLISHED {@link Pipeline} without an account. Responses are regular
 * {@code Submission} rows anchored on {@code publicLink} instead of an
 * assignment/user pair.
 */
@Entity
@Table(name = "public_assessment_links")
@Getter
@Setter
@NoArgsConstructor
public class PublicAssessmentLink extends BaseEntity {

    @Column(nullable = false, unique = true)
    private UUID token;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @Column(nullable = false)
    private String title;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PublicAssessmentLinkStatus status = PublicAssessmentLinkStatus.ACTIVE;

    @Enumerated(EnumType.STRING)
    @Column(name = "respondent_email_mode", nullable = false)
    private RespondentFieldMode respondentEmailMode = RespondentFieldMode.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "respondent_name_mode", nullable = false)
    private RespondentFieldMode respondentNameMode = RespondentFieldMode.NONE;

    @Column(name = "show_results_to_respondent", nullable = false)
    private boolean showResultsToRespondent = true;

    /** Null means unlimited responses. */
    @Column(name = "max_responses")
    private Integer maxResponses;

    @Column(name = "response_count", nullable = false)
    private int responseCount;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;
}
