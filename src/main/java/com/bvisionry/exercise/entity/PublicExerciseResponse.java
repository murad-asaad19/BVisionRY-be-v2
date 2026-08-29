package com.bvisionry.exercise.entity;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * One anonymous fill of a public exercise. Deliberately NOT an
 * {@link ExerciseSubmission}: a public respondent has no login to come back
 * to, so the member review handshake (statuses, comments, resubmit, quality
 * tags) has nobody to talk to. This is a write-once record the admin reads.
 *
 * <p>Answers are stored in whichever field matches the template's kind —
 * {@link #answers} for WORKSHEET, {@link #sheetRows} for SHEET.
 */
@Entity
@Table(name = "public_exercise_responses")
@Getter
@Setter
@NoArgsConstructor
public class PublicExerciseResponse extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private ExerciseTemplate template;

    @Column(name = "respondent_name", length = 200)
    private String respondentName;

    @Column(name = "respondent_email", length = 320)
    private String respondentEmail;

    /** WORKSHEET: block id → answer, same shape as {@link ExerciseSubmission#getAnswers()}. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private Map<String, Object> answers;

    /** SHEET: the ordered rows, each one columnId → cell. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "sheet_rows", columnDefinition = "jsonb")
    private List<Map<String, Object>> sheetRows;

    /** sha256 of token + client IP — abuse forensics without storing an IP. */
    @Column(name = "ip_hash", length = 64)
    private String ipHash;

    @Column(name = "user_agent", length = 512)
    private String userAgent;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt;
}
