package com.bvisionry.pipeline.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.PipelineStatus;
import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "pipelines")
@Getter
@Setter
@NoArgsConstructor
public class Pipeline extends BaseEntity {

    @Column(nullable = false)
    private String name;

    private String description;

    @Column(nullable = false)
    private int version = 1;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private PipelineStatus status = PipelineStatus.DRAFT;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @Column(name = "free_tier_prompt", columnDefinition = "TEXT")
    private String freeTierPrompt;

    @Column(name = "overall_summary_prompt", columnDefinition = "TEXT")
    private String overallSummaryPrompt;

    @OneToMany(mappedBy = "pipeline", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder")
    private List<Pillar> pillars = new ArrayList<>();

    @Column(name = "post_completion_survey_id")
    private UUID postCompletionSurveyId;

    @Column(name = "post_completion_external_url", columnDefinition = "TEXT")
    private String postCompletionExternalUrl;

    @Column(name = "post_completion_label", length = 120)
    private String postCompletionLabel;
}
