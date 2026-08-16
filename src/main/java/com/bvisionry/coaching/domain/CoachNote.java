package com.bvisionry.coaching.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A private note a coach keeps about one founder (redesign spec §2.2/§2.4).
 * Visible to coaches who can see the founder and to org admins (labeled with
 * the coach's name) — never to the member. Ids are bare UUIDs, same
 * soft-coupling stance as {@link CoachAssignment}.
 */
@Entity
@Table(name = "coach_notes")
@Getter
@Setter
public class CoachNote {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "coach_id", nullable = false, updatable = false)
    private UUID coachId;

    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "body", nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        createdAt = OffsetDateTime.now();
        updatedAt = createdAt;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
