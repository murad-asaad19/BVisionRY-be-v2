package com.bvisionry.programflow.dto;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import com.bvisionry.common.enums.SessionType;
import com.bvisionry.programflow.domain.AudienceMode;
import com.bvisionry.programflow.domain.MilestoneRole;
import com.bvisionry.programflow.domain.ModuleLockMode;
import com.bvisionry.programflow.domain.ProgramTaskStatus;
import com.bvisionry.programflow.domain.ProgramTaskType;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * The Curriculum builder's explicit Save: the WHOLE board as the admin holds
 * it, applied in one write. The builder edits locally and sends this once, so
 * the payload is a complete snapshot — anything it does not carry is deleted.
 *
 * <p><strong>Ids are the client's.</strong> A module/task/field the admin added
 * arrives with a UUID the browser minted, so the payload is a snapshot rather
 * than a create-then-reference dance. An id the server has never seen is
 * created under it; an id belonging to a DIFFERENT cohort is rejected.
 *
 * <p>{@code position} is not sent: it is re-derived from list order, so a saved
 * board never carries a gap.
 */
public record SaveBoardRequest(
        /**
         * The {@link BoardResponse#version()} this board was built on. Since the
         * payload is COMPLETE, a save against a stale board deletes everything
         * another admin added meanwhile — so a mismatch is a 412, not a merge.
         */
        @NotNull Long expectedVersion,
        /** Confirms deleting tasks members have already worked on — see the 409. */
        boolean force,
        @NotNull @Valid List<ModuleUpsert> modules) {

    public record ModuleUpsert(
            @NotNull UUID id,
            @NotBlank @Size(max = 200) String name,
            String summary,
            @Size(max = 120) String pillarLabel,
            boolean paced,
            @NotNull ModuleLockMode lockMode,
            OffsetDateTime unlockAt,
            @NotNull AudienceMode assignMode,
            List<UUID> memberIds,
            @NotNull @Valid List<TaskUpsert> tasks) {

        public ModuleUpsert {
            memberIds = memberIds == null ? List.of() : memberIds;
        }
    }

    public record TaskUpsert(
            @NotNull UUID id,
            @NotBlank @Size(max = 200) String name,
            LocalDate dueDate,
            @NotNull ProgramTaskStatus status,
            boolean aiDraft,
            @NotNull ProgramTaskType taskType,
            UUID refId,
            MilestoneRole milestoneRole,
            /** SESSION only (spec §3.1): 1:1 / group coaching / workshop. */
            SessionType sessionType,
            /** SESSION only (spec §3.1): slot length, 15–240 minutes. */
            Integer durationMinutes,
            /** SESSION only: the optional post-session survey. */
            UUID postSessionSurveyId,
            /**
             * The cohort's mapped DISTANCE pillar ids this task grows (spec §1).
             * Optional, and refused on an ASSESSMENT task — a pipeline
             * assessment is already pillar-linked through its pipeline.
             */
            List<UUID> pillarIds,
            @NotNull @Valid List<FieldUpsert> fields) {

        public TaskUpsert {
            pillarIds = pillarIds == null ? List.of() : pillarIds;
        }

        /** A task of any type but SESSION — no subtype, no length, no follow-up survey. */
        public TaskUpsert(UUID id, String name, LocalDate dueDate, ProgramTaskStatus status,
                boolean aiDraft, ProgramTaskType taskType, UUID refId, MilestoneRole milestoneRole,
                List<UUID> pillarIds, List<FieldUpsert> fields) {
            this(id, name, dueDate, status, aiDraft, taskType, refId, milestoneRole,
                    null, null, null, pillarIds, fields);
        }
    }
}
