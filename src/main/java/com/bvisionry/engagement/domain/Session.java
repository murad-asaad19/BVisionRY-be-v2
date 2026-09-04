package com.bvisionry.engagement.domain;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import com.bvisionry.common.enums.SessionType;

import jakarta.persistence.CollectionTable;
import jakarta.persistence.Column;
import jakarta.persistence.ElementCollection;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One cohort session (spec §4): a workshop, 1:1 or group coaching event whose
 * attendance feeds the participation score. Expected attendees default to the
 * cohort's members AT READ TIME; {@link #expectedMemberIds} narrows a session
 * (e.g. a 1:1 names its one founder) so everyone else's participation
 * denominator excludes it. Soft-coupled to identity/cohorts by UUID, like the
 * rest of the codebase.
 */
@Entity
@Table(name = "sessions")
@Getter
@Setter
public class Session {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    @Enumerated(EnumType.STRING)
    @Column(name = "type", nullable = false, length = 20)
    private SessionType type;

    @Column(name = "title", length = 200)
    private String title;

    /**
     * When the session is held. NULL only on a task-backed row still
     * UNSCHEDULED (v2 spec §2): the row is materialised the moment the cohort
     * reaches the task and only dated when it is booked or scheduled. A plain
     * session is always dated ({@code ck_sessions_booking_shape}).
     */
    @Column(name = "session_date")
    private OffsetDateTime sessionDate;

    /** EMPTY = the whole cohort is expected; rows narrow the session. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "session_expected_attendees",
            joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "member_id", nullable = false)
    private Set<UUID> expectedMemberIds = new LinkedHashSet<>();

    /**
     * The pillars this session grows (V207) — DISTANCE pillar ids, same as
     * {@code program_task_pillars}, so a tag lines up 1:1 with the narrative it
     * feeds. Optional; the upsert validates against the cohort's mapped pairs.
     */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "session_pillars",
            joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "pillar_id", nullable = false)
    private Set<UUID> pillarIds = new LinkedHashSet<>();

    @Column(name = "created_by")
    private UUID createdBy;

    /*
     * Booking columns (V215, coaching-sessions spec §2.2). A booking IS a
     * session: the member's 1:1 booked against their coach for a
     * SESSION task. All null on a plain session; the coaching and calendar
     * slices write them (raw SQL) and this slice only ever READS them — the Sessions
     * tab refuses to edit or delete a booked row.
     */

    /** The SESSION task this row answers; null = a plain session. */
    @Column(name = "program_task_id", updatable = false)
    private UUID programTaskId;

    /** The founder a 1:1 row belongs to; NULL on a cohort-wide row. */
    @Column(name = "member_id", updatable = false)
    private UUID memberId;

    @Column(name = "coach_id", updatable = false)
    private UUID coachId;

    @Column(name = "ends_at", updatable = false)
    private OffsetDateTime endsAt;

    /** UNSCHEDULED | SCHEDULED | COMPLETED — see v2 spec §2 for what each means. */
    @Column(name = "booking_status", length = 20, updatable = false)
    private String bookingStatus;

    @Column(name = "completed_at", updatable = false)
    private OffsetDateTime completedAt;

    /** The provider's event id once the coach's calendar carries this session. */
    @Column(name = "calendar_event_id", updatable = false)
    private String calendarEventId;

    /** The Meet (or other provider) link the calendar event created. */
    @Column(name = "meeting_url", updatable = false)
    private String meetingUrl;

    /** True when this row is a coaching booking rather than an admin-entered session. */
    public boolean isBooking() {
        return programTaskId != null;
    }

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
