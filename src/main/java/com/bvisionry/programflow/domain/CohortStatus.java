package com.bvisionry.programflow.domain;

/**
 * A cohort's lifecycle (redesign spec §8, V167):
 * DRAFT → LAUNCHED → COMPLETED → ARCHIVED (DRAFT may archive directly).
 *
 * <ul>
 *   <li>DRAFT — free to build; invisible to members; consumes no quota.</li>
 *   <li>LAUNCHED — members work through it; the transition consumed launch quota.</li>
 *   <li>COMPLETED — read-only for members (the closing screen); admins keep editing.</li>
 *   <li>ARCHIVED — read-only for everyone; invisible to members.</li>
 * </ul>
 */
public enum CohortStatus {
    DRAFT,
    LAUNCHED,
    COMPLETED,
    ARCHIVED
}
