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

import java.util.ArrayList;
import java.util.List;
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

    @OneToMany(mappedBy = "template", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder")
    private List<ExerciseColumn> columns = new ArrayList<>();
}
