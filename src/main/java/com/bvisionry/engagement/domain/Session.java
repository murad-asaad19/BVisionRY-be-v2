package com.bvisionry.engagement.domain;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

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

    @Column(name = "session_date", nullable = false)
    private OffsetDateTime sessionDate;

    /** EMPTY = the whole cohort is expected; rows narrow the session. */
    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "session_expected_attendees",
            joinColumns = @JoinColumn(name = "session_id"))
    @Column(name = "member_id", nullable = false)
    private Set<UUID> expectedMemberIds = new LinkedHashSet<>();

    @Column(name = "created_by")
    private UUID createdBy;

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
