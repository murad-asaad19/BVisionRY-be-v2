package com.bvisionry.workshops.domain;

/**
 * Workshop lifecycle: DRAFT (editable, hidden from teams) ⇄ ACTIVE (published:
 * visible to enrolled teams, builder locked) ⇄ FINISHED (thank-you screen).
 * Unpublishing (back to DRAFT) reopens the builder and hides the workshop.
 */
public enum WorkshopStatus {
    DRAFT,
    ACTIVE,
    FINISHED
}
