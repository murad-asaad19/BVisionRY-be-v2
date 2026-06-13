package com.bvisionry.pipeline.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.QuestionType;
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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.Map;

@Entity
@Table(name = "questions")
@Getter
@Setter
@NoArgsConstructor
public class Question extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pillar_id", nullable = false)
    private Pillar pillar;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private QuestionType type;

    @Column(name = "prompt_text", nullable = false, columnDefinition = "TEXT")
    private String promptText;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired = true;

    @Column(nullable = false)
    private BigDecimal weight = BigDecimal.ONE;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb")
    private Map<String, Object> configJson;

    /**
     * Marker for system-managed questions that cannot be edited or deleted by admins.
     * Used to lock the Name + Gender questions in the Personal pillar so the AI always
     * has the data it needs to address the user correctly.
     * Known values: "FULL_NAME", "GENDER". Null for all admin-created questions.
     */
    @Column(name = "system_key", length = 64)
    private String systemKey;
}
