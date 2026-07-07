package com.bvisionry.workshops.domain;

/**
 * Workshop lifecycle: DRAFT (editable, hidden from teams) → ACTIVE (published:
 * visible to enrolled teams, builder locked) ⇄ FINISHED (thank-you screen).
 * Publishing is one-way — a published workshop never returns to DRAFT.
 */
public enum WorkshopStatus {
    DRAFT,
    ACTIVE,
    FINISHED
}
