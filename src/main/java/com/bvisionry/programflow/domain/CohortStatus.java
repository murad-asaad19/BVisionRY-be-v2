package com.bvisionry.programflow.domain;

/**
 * A cohort's lifecycle (redesign spec §8, V167, collapsed to two states in
 * V183): DRAFT ⇄ LAUNCHED, a two-way toggle.
 *
 * <ul>
 *   <li>DRAFT — free to build; invisible to members; consumes no quota.</li>
 *   <li>LAUNCHED — members work through it; the first launch consumed each
 *       assigned billing family's quota (relaunching a family that already
 *       paid is free — the ledger is append-only and never refunded).</li>
 * </ul>
 */
public enum CohortStatus {
    DRAFT,
    LAUNCHED
}
