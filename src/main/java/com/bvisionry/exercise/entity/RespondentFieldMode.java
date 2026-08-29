package com.bvisionry.exercise.entity;

/**
 * How much a public exercise asks of an anonymous respondent, per field.
 *
 * <p>Deliberately a copy of the survey slice's enum of the same name rather
 * than a shared type: the two features answer to different admins and may
 * diverge, so a rename on one side must not silently retype the other.
 *
 * <p>NOT because an exercise → survey edge is impossible — V211's paired
 * post-completion survey adds one, pinned in the ArchUnit frozen store. The
 * duplication is a deliberate decoupling, not a workaround for the rule.
 */
public enum RespondentFieldMode {
    /** Not asked at all. */
    NONE,
    /** Asked, may be left blank. */
    OPTIONAL,
    /** Asked, and submit is refused without it. */
    REQUIRED
}
