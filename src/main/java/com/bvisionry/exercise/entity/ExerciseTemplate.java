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

    @Column(columnDefinition = "TEXT")
    private String description;

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

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder")
    private List<ExerciseColumn> columns = new ArrayList<>();
}
