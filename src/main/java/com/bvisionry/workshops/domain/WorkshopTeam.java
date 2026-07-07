package com.bvisionry.workshops.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A per-workshop team. Membership lives in {@code workshop_team_members}
 * (managed via native queries on the repository); its PK on
 * {@code (workshop_id, user_id)} guarantees a user is on at most one team per
 * workshop, and a partial unique index guarantees exactly one lead per team.
 */
@Entity
@Table(name = "workshop_teams")
@Getter
@Setter
public class WorkshopTeam extends WorkshopBaseEntity {

    @Column(name = "workshop_id", nullable = false, updatable = false)
    private UUID workshopId;

    @Column(name = "name", nullable = false, length = 120)
    private String name;

    @Column(name = "position", nullable = false)
    private int position = 0;

    /** Admin-picked team-card key ('red', 'blue', …; icons later); null = frontend default by team order. */
    @Column(name = "card", length = 24)
    private String card;

    /** Open "we need help" ping (set from the timer once the soft time budget runs out); null = none. */
    @Column(name = "help_requested_at")
    private OffsetDateTime helpRequestedAt;
}
