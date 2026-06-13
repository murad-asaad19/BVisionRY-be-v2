package com.bvisionry.survey.entity;

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

import java.math.BigDecimal;
import java.util.List;

@Entity
@Table(name = "survey_answers")
@Getter
@Setter
@NoArgsConstructor
public class SurveyAnswer extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "response_id", nullable = false)
    private SurveyResponse response;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "question_id", nullable = false)
    private SurveyQuestion question;

    @Column(name = "response_text", columnDefinition = "TEXT")
    private String responseText;

    // Multi-select MULTIPLE_CHOICE answers join option labels with '|||', so
    // the stored value can easily exceed 500 characters when several long
    // options are picked. {@code TEXT} matches the migrated DB column (V61).
    @Column(name = "selected_value", columnDefinition = "TEXT")
    private String selectedValue;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "selected_values_json", columnDefinition = "jsonb")
    private List<String> selectedValues;

    @Column(name = "numeric_value", precision = 10, scale = 2)
    private BigDecimal numericValue;
}
