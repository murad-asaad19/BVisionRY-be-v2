package com.bvisionry.workshops.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One exercise inside a workshop: an ordered pipeline of
 * {@link WorkshopExerciseTask}s. Teams progress through a workshop's exercises
 * sequentially — exercise N+1 opens for a team once N is shared by its lead.
 */
@Entity
@Table(name = "workshop_exercises")
@Getter
@Setter
public class WorkshopExercise extends WorkshopBaseEntity {

    @Column(name = "workshop_id", nullable = false, updatable = false)
    private UUID workshopId;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Column(name = "position", nullable = false)
    private int position = 0;
}
