package com.bvisionry.exercise.entity;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.entity.BaseEntity;
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

import java.time.Instant;
import java.util.UUID;

/**
 * A review comment on an exercise submission. Anchor semantics: {@link #row} +
 * {@link #column} = one cell, column only = the whole column, row only = the
 * whole row, neither = the submission overall. Admins open root comments;
 * members reply via {@link #parent}. Status/resolved fields are only
 * meaningful on roots — replies inherit their root's lifecycle.
 */
@Entity
@Table(name = "exercise_comments")
@Getter
@Setter
@NoArgsConstructor
public class ExerciseComment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private ExerciseSubmission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "author_id", nullable = false)
    private User author;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "row_id")
    private ExerciseRow row;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "column_id")
    private ExerciseColumn column;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private ExerciseComment parent;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String body;

    /**
     * The commented cell's value frozen at comment time, so the thread stays
     * readable after the member edits the cell to address the feedback.
     */
    @Column(name = "cell_value_snapshot", columnDefinition = "TEXT")
    private String cellValueSnapshot;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExerciseCommentStatus status = ExerciseCommentStatus.OPEN;

    @Column(name = "resolved_by")
    private UUID resolvedBy;

    @Column(name = "resolved_at")
    private Instant resolvedAt;
}
