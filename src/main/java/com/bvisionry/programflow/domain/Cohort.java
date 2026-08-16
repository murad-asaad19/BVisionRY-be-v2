package com.bvisionry.programflow.domain;

import java.time.OffsetDateTime;
import java.util.LinkedHashSet;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.CollectionTable;
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
 * A cohort: a platform-level program run (spec §13). Authored by super admins
 * and ASSIGNED to organizations via {@link CohortOrgAssignment}; owns its
 * modules and settings (both FK cohort_id), carries the enrolled learner set
 * (accumulated across assigned orgs) and the DRAFT ⇄ LAUNCHED lifecycle
 * (spec §8, two-state since V183). Soft-coupled to identity by UUID, like the
 * rest of the slice.
 */
@Entity
@Table(name = "cohorts")
@Getter
@Setter
public class Cohort {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private CohortStatus status = CohortStatus.DRAFT;

    /**
     * Stamped by the FIRST launch and never cleared — the floor for milestone
     * adoption ({@code TaskSpineRepository.ADOPTABLE_SITTINGS}). Null only for
     * a cohort that has never been launched; an unlaunched cohort keeps it.
     */
    @Column(name = "launched_at")
    private OffsetDateTime launchedAt;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "cohort_members", joinColumns = @JoinColumn(name = "cohort_id"))
    @Column(name = "user_id", nullable = false)
    private Set<UUID> memberIds = new LinkedHashSet<>();

    /**
     * Optimistic concurrency for the curriculum (V175). Bumped by the ONE
     * writer of modules/tasks/fields — the Curriculum builder's whole-board Save
     * — and echoed on every board read, so a Save built against a stale board is
     * refused instead of deleting whatever the other admin added meanwhile.
     *
     * <p>Not a JPA {@code @Version}: this tracks the BOARD, and the board lives
     * in other tables entirely. Hibernate would bump it on any cohort-row write
     * (a rename, a launch) and leave it untouched when a module changed, which
     * is exactly backwards.
     */
    @Column(name = "board_version", nullable = false)
    private long boardVersion;

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
