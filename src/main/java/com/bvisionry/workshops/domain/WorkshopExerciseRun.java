package com.bvisionry.workshops.domain;

import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The per-(exercise, team) run anchor, created when the team's lead first
 * enters the exercise. {@code sharedAt} is set when the lead completes their
 * last task — that is the "results shared with the team" moment that unlocks
 * member tasks. {@code deals} pins each SORT task's randomly dealt,
 * side-balanced card subset ({@code taskId → [cardId…]}) so retries and the
 * downstream WEIGHT/TOP/QUESTION chain all see the same hand.
 */
@Entity
@Table(name = "workshop_exercise_runs")
@Getter
@Setter
public class WorkshopExerciseRun extends WorkshopBaseEntity {

    @Column(name = "exercise_id", nullable = false, updatable = false)
    private UUID exerciseId;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "shared_at")
    private OffsetDateTime sharedAt;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "deals", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> deals = new LinkedHashMap<>();
}
