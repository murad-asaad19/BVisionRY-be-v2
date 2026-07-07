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

    /**
     * Per-response token minted when a gift assessment is emailed for this public
     * survey response. Travels in the gift link ({@code /a/<link>?g=<giftToken>})
     * and lets the gifted submission be tied back to THIS response (via
     * {@link #giftSubmission}) when the respondent starts it — instead of guessing
     * by shared email. Null for responses that never received a gift.
     */
    @Column(name = "gift_token", unique = true)
    private UUID giftToken;

    /**
     * The gifted public-assessment submission the respondent started from this
     * response's gift email, matched via {@link #giftToken}. Distinct from
     * {@link #submission} (the post-assessment link): the survey response is the
     * primary artifact for a gift, so this FK is {@code ON DELETE SET NULL} —
     * deleting the gifted submission detaches it rather than cascading the
     * response away. Null until (and unless) the respondent opens the gift.
     */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "gift_submission_id")
    private Submission giftSubmission;

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

    /**
     * The workshop this response gates, set for {@link ResponseSource#WORKSHOP_INTRO}
     * pre-workshop survey submissions. A plain id (not an entity relation) keeps the
     * survey slice decoupled from the workshops slice. Null for every other flow.
     */
    @Column(name = "workshop_id")
    private UUID workshopId;

    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "cookie_id", length = 64)
    private String cookieId;

    @Column(name = "user_agent")
    private String userAgent;

    @OneToMany(mappedBy = "response", cascade = CascadeType.ALL, orphanRemoval = true)
    private List<SurveyAnswer> answers = new ArrayList<>();
}
