package com.bvisionry.quiz.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A graded submission attempt by a user for a quiz lesson.
 */
@Entity
@Table(name = "quiz_attempt")
@Getter
@Setter
public class QuizAttempt {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "content_id", nullable = false)
    private UUID contentId;

    @Column(name = "user_id", nullable = false)
    private UUID userId;

    @Column(name = "enrollment_id", nullable = false)
    private UUID enrollmentId;

    @Column(name = "score_pct", nullable = false)
    private int scorePct;

    @Column(name = "passed", nullable = false)
    private boolean passed;

    @Column(name = "submitted_at", nullable = false, updatable = false)
    private OffsetDateTime submittedAt;

    @OneToMany(mappedBy = "attempt", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    private List<QuizAttemptAnswer> answers = new ArrayList<>();

    @PrePersist
    void onCreate() {
        if (submittedAt == null) {
            submittedAt = OffsetDateTime.now();
        }
    }
}
