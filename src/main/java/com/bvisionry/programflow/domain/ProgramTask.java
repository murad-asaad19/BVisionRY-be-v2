package com.bvisionry.programflow.domain;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import com.bvisionry.common.enums.SessionType;
import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.CascadeType;
import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A card on the program board: a multi-step form the learner completes one
 * field per step. Stays {@code DRAFT} (invisible to learners) until published.
 */
@Entity
@Table(name = "program_tasks")
@Getter
@Setter
public class ProgramTask {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "module_id", nullable = false)
    private ProgramModule module;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    /** What kind of work this task is; LESSON keeps the form-fields flow. */
    @Enumerated(EnumType.STRING)
    @Column(name = "task_type", nullable = false, length = 20)
    private ProgramTaskType taskType = ProgramTaskType.LESSON;

    /**
     * The referenced course / exercise template / pipeline / workshop / survey
     * — a bare uuid (no FK across slices). Null for LESSON; required before a
     * non-LESSON task may go LIVE.
     */
    @Column(name = "ref_id")
    private UUID refId;

    /** Spec §5 milestone role; set iff {@link #taskType} is ASSESSMENT. */
    @Enumerated(EnumType.STRING)
    @Column(name = "milestone_role", length = 20)
    private MilestoneRole milestoneRole;

    /**
     * SESSION only (sessions spec v2 §2): which of the three session shapes
     * this task holds. Mirrored onto {@code sessions.type} when the row is
     * materialised, so participation scoring keys the same category as a
     * hand-entered session. Required for SESSION, refused for every other type
     * ({@code ck_program_tasks_session_shape}).
     */
    @Enumerated(EnumType.STRING)
    @Column(name = "session_type", length = 20)
    private SessionType sessionType;

    /** SESSION only (spec §3.1): how long a scheduled slot lasts, 15–240. */
    @Column(name = "duration_minutes")
    private Integer durationMinutes;

    /**
     * SESSION only: the survey offered to the member once the session is
     * held and they are marked present. A bare uuid into the survey slice, like
     * {@code pipelines.post_completion_survey_id}. Null = no follow-up survey.
     */
    @Column(name = "post_session_survey_id")
    private UUID postSessionSurveyId;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private ProgramTaskStatus status = ProgramTaskStatus.DRAFT;

    @Column(name = "ai_draft", nullable = false)
    private boolean aiDraft = false;

    @Column(name = "position", nullable = false)
    private int position = 0;

    /** When the due-soon reminder went out; null = not yet reminded (send-once). */
    @Column(name = "due_reminder_sent_at")
    private OffsetDateTime dueReminderSentAt;

    @OneToMany(mappedBy = "task", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<ProgramTaskField> fields = new ArrayList<>();

    /**
     * The pillars this task grows (redesign spec §1) — DISTANCE pillar ids, so a
     * tag lines up 1:1 with the narrative it feeds. Optional and never set on an
     * ASSESSMENT task: a pipeline assessment is already pillar-linked through
     * its pipeline. Read-only here — the board Save's raw-SQL restore is the
     * only writer, exactly like the module audience.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "program_task_pillars", joinColumns = @JoinColumn(name = "task_id"))
    @Column(name = "pillar_id", nullable = false)
    private Set<UUID> pillarIds = new HashSet<>();

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
