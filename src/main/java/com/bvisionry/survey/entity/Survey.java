package com.bvisionry.survey.entity;

import com.bvisionry.common.entity.BaseEntity;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

@Entity
@Table(name = "surveys")
@Getter
@Setter
@NoArgsConstructor
public class Survey extends BaseEntity {

    @Column(nullable = false)
    private String name;

    @Column(columnDefinition = "TEXT")
    private String description;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SurveyStatus status = SurveyStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private SurveyVisibility visibility = SurveyVisibility.PRIVATE;

    @Column(name = "public_token", unique = true)
    private UUID publicToken;

    @Column(name = "published_at")
    private Instant publishedAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "respondent_email_mode", nullable = false)
    private RespondentFieldMode respondentEmailMode = RespondentFieldMode.NONE;

    @Enumerated(EnumType.STRING)
    @Column(name = "respondent_name_mode", nullable = false)
    private RespondentFieldMode respondentNameMode = RespondentFieldMode.NONE;

    @Column(name = "created_by", nullable = false)
    private UUID createdBy;

    @OneToMany(mappedBy = "survey", cascade = CascadeType.ALL, orphanRemoval = true)
    @OrderBy("displayOrder")
    private List<SurveyPillar> pillars = new ArrayList<>();
}
