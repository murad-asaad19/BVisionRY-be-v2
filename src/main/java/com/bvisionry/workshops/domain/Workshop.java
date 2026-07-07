package com.bvisionry.workshops.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An org-scoped workshop: a sequential container of card-based team exercises
 * (Control-Flip style). Teams, exercises and runs all hang off it. Soft-coupled
 * to identity and to the optional post-completion survey by UUID, like the rest
 * of the slice.
 */
@Entity
@Table(name = "workshops")
@Getter
@Setter
public class Workshop extends WorkshopBaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private WorkshopStatus status = WorkshopStatus.ACTIVE;

    @Column(name = "post_completion_survey_id")
    private UUID postCompletionSurveyId;

    /** Live-board road style key ('lanes', 'track', …); null = frontend default. */
    @Column(name = "board_style", length = 24)
    private String boardStyle;
}
