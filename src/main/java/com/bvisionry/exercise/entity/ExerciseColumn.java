package com.bvisionry.exercise.entity;

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
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.Map;

/**
 * One column of an exercise sheet. A real entity (not a jsonb blob on the
 * template) so review comments have a stable FK anchor that survives renames
 * and reorders.
 */
@Entity
@Table(name = "exercise_columns")
@Getter
@Setter
@NoArgsConstructor
public class ExerciseColumn extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "template_id", nullable = false)
    private ExerciseTemplate template;

    @Column(nullable = false)
    private String name;

    /** Help text shown under the column header while filling. */
    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private ExerciseColumnType type = ExerciseColumnType.TEXT;

    /** Per-type config, e.g. {@code {options: [...]}} for SELECT. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config_json", columnDefinition = "jsonb")
    private Map<String, Object> configJson;

    @Column(name = "display_order", nullable = false)
    private int displayOrder = 0;

    @Column(name = "is_required", nullable = false)
    private boolean isRequired = false;
}
