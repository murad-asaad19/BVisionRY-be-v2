package com.bvisionry.workshops.domain;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One task of an exercise pipeline. The per-type shape lives in {@code config}
 * JSONB (same pattern as {@code program_task_fields.config}):
 *
 * <pre>
 * shared:   steps (one instruction line per row), envTitle, envText, celebrate
 * SORT:     instructions, leftLabel, rightLabel, retryAfter,
 *           dealPerTeam (null/0 = deal all), cards [{id, text, correct}]
 * TOP:      count
 * QUESTION: prompt
 * </pre>
 *
 * Auto-wiring (WEIGHT ← nearest SORT above, TOP ← nearest WEIGHT above,
 * QUESTION ← nearest TOP above) is derived from {@code position} at read time,
 * never stored — reordering re-wires automatically. The SORT answer key
 * ({@code cards[].correct}) never leaves the server.
 */
@Entity
@Table(name = "workshop_exercise_tasks")
@Getter
@Setter
public class WorkshopExerciseTask extends WorkshopBaseEntity {

    @Column(name = "exercise_id", nullable = false, updatable = false)
    private UUID exerciseId;

    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private WorkshopTaskType taskType;

    @Enumerated(EnumType.STRING)
    @Column(name = "assignee", nullable = false, length = 20)
    private WorkshopTaskAssignee assignee = WorkshopTaskAssignee.LEAD;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "config", columnDefinition = "jsonb", nullable = false)
    private Map<String, Object> config = new LinkedHashMap<>();
}
