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
 * One performed task. LEAD tasks: one row per (task, team) with
 * {@code userId} = the lead. MEMBER tasks: one row per (task, team, user).
 * {@code payload} per type:
 *
 * <pre>
 * SORT:     { sorted: {cardId: "left"|"right"}, wrongIds: [cardId…] }
 * WEIGHT:   { weights: {cardId: 0..100} }
 * TOP:      { }
 * QUESTION: { answers: [{cardId, text}…] }  (legacy rows: { cardId, text })
 * </pre>
 *
 * {@code startedAt} is set by "Start Task" (the count-up timer origin);
 * {@code completedAt}/{@code elapsedMs} on completion. {@code attempts} counts
 * graded SORT attempts. Analytics are derived from these rows.
 */
@Entity
@Table(name = "workshop_task_submissions")
@Getter
@Setter
public class WorkshopTaskSubmission extends WorkshopBaseEntity {

    @Column(name = "task_id", nullable = false, updatable = false)
    private UUID taskId;

    @Column(name = "team_id", nullable = false, updatable = false)
    private UUID teamId;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "payload", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> payload = new LinkedHashMap<>();

    @Column(name = "attempts", nullable = false)
    private int attempts = 0;

    @Column(name = "started_at")
    private OffsetDateTime startedAt;

    @Column(name = "completed_at")
    private OffsetDateTime completedAt;

    @Column(name = "elapsed_ms")
    private Long elapsedMs;

    public boolean isCompleted() {
        return completedAt != null;
    }
}
