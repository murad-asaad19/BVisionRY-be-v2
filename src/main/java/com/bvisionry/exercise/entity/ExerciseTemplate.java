package com.bvisionry.exercise.entity;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.CascadeType;
import jakarta.persistence.OneToMany;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A super-admin-authored, sheet-like exercise: a fixed set of typed columns
 * that members fill with as many rows as they need. Provisioned to
 * organizations and distributed to members exactly like assessment pipelines,
 * but with a human (admin comments) review loop instead of an AI evaluation.
 */
@Entity
@Table(name = "exercise_templates")
@Getter
@Setter
@NoArgsConstructor
public class ExerciseTemplate extends BaseEntity {

    @Column(nullable = false)
    private String name;

    /** Sheet or worksheet — set at creation, never changed (see the enum's doc). */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExerciseTemplateKind kind = ExerciseTemplateKind.SHEET;

    /**
     * WORKSHEET only: the ordered block list, as one jsonb document. Null for
     * SHEET templates (whose structure lives in {@link #columns}).
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private List<WorksheetBlock> blocks;

    /**
     * The brief the member reads above their sheet, as a serialised tiptap
     * document (same shape and column style as {@code content.body}). Rows
     * written before V177 were plain text and were lifted into a document by
     * that migration, so readers have exactly one format to handle.
     */
    @Column(columnDefinition = "TEXT")
    private String description;

    /**
     * Cover art shown above the brief. Holds either a {@code minio://bucket/key}
     * marker resolved at read time or an external URL — see
     * {@link com.bvisionry.common.media.MediaUrlPort}.
     */
    @Column(name = "cover_image_url", length = 500)
    private String coverImageUrl;

    /**
     * Staff-only brief for the AI: what this exercise is for, written by an
     * admin in the builder and NEVER shown to members. The shift narrative's
     * ACTIVITY section includes it so the model reads a submission knowing what
     * the task was asking, instead of inferring purpose from column names.
     */
    @Column(name = "ai_context", columnDefinition = "TEXT")
    private String aiContext;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExerciseTemplateStatus status = ExerciseTemplateStatus.DRAFT;

    @Column(name = "created_by", nullable = false, updatable = false)
    private UUID createdBy;

    /**
     * Optional read-only sample row (columnId → value) shown greyed out above
     * the member's sheet as guidance — never part of their answer.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "example_row", columnDefinition = "jsonb")
    private Map<String, Object> exampleRow;

    /**
     * Rows (columnId → value each) seeded into every NEW member submission,
     * e.g. prefilled "Round 1/2/3" labels. Members can fill their unlocked
     * cells but never delete these rows.
     */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "starter_rows", columnDefinition = "jsonb")
    private List<Map<String, Object>> starterRows;

    /** When false the sheet is fixed to its starter rows — no member-added rows. */
    @Column(name = "allow_add_rows", nullable = false)
    private boolean allowAddRows = true;

    /**
     * Public link gate. When true AND the template is
     * {@link ExerciseTemplateStatus#PUBLISHED}, anyone holding
     * {@link #publicToken} can fill this exercise anonymously.
     */
    @Column(name = "is_public", nullable = false)
    private boolean isPublic = false;

    /**
     * The token in the public URL ({@code /exercise/{token}}) and in the QR code.
     * Minted the first time the exercise goes public and never cleared, so a
     * printed QR survives an unpublish/republish.
     */
    @Column(name = "public_token", unique = true)
    private UUID publicToken;

    /**
     * Optional survey the PUBLIC taker is offered after submitting (V211) — the
     * same "what next" pairing as {@code pipelines.post_completion_survey_id}.
     * A bare id, not a relation: no JPA edges across features (the convention
     * {@code Survey#giftPublicAssessmentLinkId} already follows).
     *
     * <p>Public link only. A member has a review loop to come back to, not a
     * thank-you screen, so their flow is deliberately untouched.
     */
    @Column(name = "post_completion_survey_id")
    private UUID postCompletionSurveyId;

    @Enumerated(EnumType.STRING)
    @Column(name = "respondent_name_mode", nullable = false)
    private RespondentFieldMode respondentNameMode = RespondentFieldMode.OPTIONAL;

    @Enumerated(EnumType.STRING)
    @Column(name = "respondent_email_mode", nullable = false)
    private RespondentFieldMode respondentEmailMode = RespondentFieldMode.OPTIONAL;

    /**
     * Whether the public link STORES respondents' fills (V213). When false the
     * taker keeps work in the browser only and offers no submit, and
     * {@link com.bvisionry.exercise.PublicExerciseService#submit} refuses a
     * write — nothing an anonymous visitor types reaches the server.
     */
    @Column(name = "save_public_responses", nullable = false)
    private boolean savePublicResponses = true;

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder")
    private List<ExerciseColumn> columns = new ArrayList<>();
}
