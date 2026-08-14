package com.bvisionry.programflow.domain;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

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
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A drip-scheduled stage of the program: a kanban column on the admin board,
 * a timeline node on the learner journey. Owns its tasks and its audience
 * (everyone / selected teams / selected members).
 */
@Entity
@Table(name = "program_modules")
@Getter
@Setter
public class ProgramModule {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    /** Owning cohort — the module's whole scope; audience is roster-scoped (spec §13). */
    @Column(name = "cohort_id", nullable = false)
    private UUID cohortId;

    @Column(name = "name", nullable = false, length = 200)
    private String name;

    @Column(name = "summary")
    private String summary;

    /**
     * The module's pillar/area chip (spec §2.3; D2 journey seam). Free label
     * this phase — a structured pillar reference is a later, deliberate change.
     */
    @Column(name = "pillar_label", length = 120)
    private String pillarLabel;

    /**
     * Whether this module is a NUMBERED STAGE of the drip ("Week 01") or
     * always-on material that sits outside the pacing — a welcome section, a
     * closing letter. False suppresses the stage kicker everywhere it renders.
     * Orthogonal to {@code lockMode}: an unpaced module may still be gated.
     */
    @Column(nullable = false)
    private boolean paced = true;

    @Column(name = "position", nullable = false)
    private int position = 0;

    @Enumerated(EnumType.STRING)
    @Column(name = "lock_mode", nullable = false, length = 20)
    private ModuleLockMode lockMode = ModuleLockMode.SEQUENTIAL;

    @Column(name = "unlock_at")
    private OffsetDateTime unlockAt;

    /** When the module-unlocked push went out; null = not yet notified (send-once). */
    @Column(name = "unlock_notified_at")
    private OffsetDateTime unlockNotifiedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "assign_mode", nullable = false, length = 20)
    private AudienceMode assignMode = AudienceMode.ALL;

    @ElementCollection(fetch = FetchType.LAZY)
    @CollectionTable(name = "program_module_members", joinColumns = @JoinColumn(name = "module_id"))
    @Column(name = "user_id", nullable = false)
    private Set<UUID> memberIds = new LinkedHashSet<>();

    @OneToMany(mappedBy = "module", cascade = CascadeType.ALL, orphanRemoval = true, fetch = FetchType.LAZY)
    @OrderBy("position ASC")
    private List<ProgramTask> tasks = new ArrayList<>();

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
