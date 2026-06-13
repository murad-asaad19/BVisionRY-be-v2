package com.bvisionry.quiz.domain;

import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A selectable answer option within a {@link QuizQuestion}.
 */
@Entity
@Table(name = "quiz_option")
@Getter
@Setter
public class QuizOption {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "question_id", nullable = false)
    private QuizQuestion question;

    @Column(name = "text", nullable = false, length = 500)
    private String text;

    @Column(name = "is_correct", nullable = false)
    private boolean isCorrect = false;

    @Column(name = "sequence", nullable = false)
    private int sequence = 0;
}
