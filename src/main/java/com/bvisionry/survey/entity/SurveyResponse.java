package com.bvisionry.survey.entity;

import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "survey_responses")
@Getter
@Setter
@NoArgsConstructor
public class SurveyResponse extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "survey_id", nullable = false)
    private Survey survey;

    /**
     * Set when the response is submitted as part of the post-assessment flow,
     * tying this response to the specific submission that triggered the survey
     * invitation. Null for legacy public-link responses.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id")
    private Submission submission;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ResponseSource source;

    @Column(name = "respondent_email")
    private String respondentEmail;

    @Column(name = "respondent_name")
    private String respondentName;

    @Column(name = "respondent_user_id")
    private UUID respondentUserId;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "cookie_id", length = 64)
    private String cookieId;

    @Column(name = "user_agent")
    private String userAgent;

    @OneToMany(mappedBy = "response", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SurveyAnswer> answers = new ArrayList<>();
}
