package com.bvisionry.programflow.dto;

import java.time.Instant;
import java.util.UUID;

import com.bvisionry.programflow.domain.ProgramTaskType;

/**
 * Result of POST /api/my/program/tasks/{id}/open — the idempotent "make sure
 * my prerequisite exists and tell me where to go" action (redesign spec §2.1).
 * {@code targetId} per type: LESSON → the task id (existing player); COURSE →
 * the course id (enrollment ensured); EXERCISE → the member's exercise
 * submission id (assignment ensured); ASSESSMENT → the member's submission id
 * tagged to this task (assignment + tagged submission ensured); WORKSHOP /
 * SURVEY → the ref id (nothing to create).
 */
public record OpenTaskResponse(
        UUID taskId,
        ProgramTaskType taskType,
        UUID refId,
        UUID targetId,
        /** §7b stamp for the open action. */
        Instant openedAt) {
}
