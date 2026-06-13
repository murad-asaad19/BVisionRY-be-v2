package com.bvisionry.pipeline.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.PillarType;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

@Entity
@Table(name = "pillars")
@Getter
@Setter
@NoArgsConstructor
public class Pillar extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "pipeline_id", nullable = false)
    private Pipeline pipeline;

    @Column(nullable = false)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PillarType type = PillarType.STANDARD;

    private String description;

    @Column(name = "icon_key")
    private String iconKey;

    @Column(nullable = false)
    private BigDecimal weight = BigDecimal.ONE;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "ai_rubric_instructions", columnDefinition = "TEXT")
    private String aiRubricInstructions;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "maturity_thresholds_json", columnDefinition = "jsonb")
    private Map<String, List<Integer>> maturityThresholds;

    @OneToMany(mappedBy = "pillar", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder")
    private List<Question> questions = new ArrayList<>();
}
