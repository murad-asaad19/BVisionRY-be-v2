package com.bvisionry.comparison.domain;

/**
 * The review gate's two states (spec §6). A DRAFT is visible ONLY to admins and
 * coaches on the founder profile; APPROVED is the single gate the member's My
 * Growth and the PDF/Excel exports read through.
 */
public enum NarrativeStatus {
    DRAFT,
    APPROVED
}
