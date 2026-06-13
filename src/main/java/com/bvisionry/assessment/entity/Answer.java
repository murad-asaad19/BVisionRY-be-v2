package com.bvisionry.assessment.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.pipeline.entity.Question;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;

@Entity
@Table(name = "answers")
@Getter
@Setter
@NoArgsConstructor
public class Answer extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "submission_id", nullable = false)
    private Submission submission;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private Question question;

    @Column(name = "response_text", columnDefinition = "TEXT")
    private String responseText;

    @Column(name = "selected_value", columnDefinition = "TEXT")
    private String selectedValue;

    @Column(name = "raw_score")
    private BigDecimal rawScore;
}
