package com.bvisionry.common.programaccess;

/**
 * The single SQL source of truth for "does this assessment sitting belong to
 * the cohort's own instruments?" — the pipelines referenced by the cohort's
 * LIVE {@code ASSESSMENT} program tasks ({@code program_tasks.ref_id}, via the
 * cohort's {@code program_modules}).
 *
 * <p>Org-admin COHORT surfaces (the founders matrix FRI cell, the cohort
 * roster's FRI · Δ, the member-in-cohort readiness card and pillar table) quote
 * readiness FROM the cohort's instruments only: a founder's sitting on an
 * unrelated instrument — a scan another team assigned directly — must never
 * stand in for this cohort's baseline. Global surfaces (founder profile, coach
 * console) deliberately keep the member's overall latest and do NOT use this.
 *
 * <p>Lives in {@code common} for the same ArchUnit reason as
 * {@link TaskCompletion}: both the {@code cohortview} and the cohort-board
 * read slices compose it, and feature→feature imports are ratcheted shut.
 */
public final class CohortInstruments {

    private CohortInstruments() {}

    /**
     * Boolean SQL expression: the {@code submissions} row aliased {@code %1$s}
     * in the composing query sits on one of the instruments of the cohort
     * given by {@code %2$s} (a bind parameter or column). Inner aliases are
     * prefixed {@code ci*} so they never shadow a composing query's own.
     */
    public static final String ON_COHORT_INSTRUMENT = """
            EXISTS (SELECT 1
                      FROM assignments cia
                      JOIN program_tasks cit ON cit.ref_id = cia.pipeline_id
                                            AND cit.task_type = 'ASSESSMENT'
                                            AND cit.status = 'LIVE'
                      JOIN program_modules cim ON cim.id = cit.module_id
                     WHERE cia.id = %1$s.assignment_id
                       AND cim.cohort_id = %2$s)""";
}
