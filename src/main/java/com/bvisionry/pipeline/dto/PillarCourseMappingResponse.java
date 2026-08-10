package com.bvisionry.pipeline.dto;

import java.util.UUID;

/**
 * One pillar -> course rule, with both ends resolved as they stand TODAY.
 *
 * <p>{@code bandPosition} is the stored identity (RULING 4: bands are per-pillar
 * data, so position — not a name — is what survives an edit). Everything band-
 * shaped beside it is DERIVED from the pillar's current thresholds on every
 * read, which is what makes a re-labelled or re-split band show up in the admin
 * UI immediately instead of quietly meaning something else.
 *
 * <p>{@code bandLabel}, {@code bandMinScore} and {@code bandMaxScore} are
 * {@code null} together when the position no longer exists — the pillar's band
 * set was shrunk under the rule. Such a rule is inert (no score can produce a
 * position outside the current set) and is reported rather than deleted, so the
 * admin can re-point it or remove it deliberately.
 *
 * <p>{@code courseTitle}/{@code courseState} are the catalog's current answer,
 * read through {@code CourseCatalogReadRepository}. A non-{@code PUBLISHED}
 * course is shown rather than hidden: an admin is entitled to know why a rule
 * they wrote will not reach anyone.
 *
 * <p><strong>{@code courseState} is a REFUSAL INPUT, not decoration.</strong> This
 * surface deliberately declines to filter: an admin may point a rule at a course
 * they are about to publish, and hiding it would make the rule look lost. That
 * means the consumer owns the refusal, and it does —
 * {@code AutoEnrolmentService} skips any rule whose course is not
 * {@code PUBLISHED} and records the skip as
 * {@code AutoEnrolmentOutcome.COURSE_NOT_PUBLISHED}; the predicate itself lives in
 * {@code CourseCatalogReadRepository#findPublishedIds} rather than in a caller's
 * {@code if}. Pinned by
 * {@code PillarCourseMappingServiceTest#everyRowCarriesCourseState...} so the
 * field cannot quietly disappear from under that consumer.
 */
public record PillarCourseMappingResponse(
        UUID id,
        UUID pillarId,
        int bandPosition,
        String bandLabel,
        Integer bandMinScore,
        Integer bandMaxScore,
        UUID courseId,
        String courseTitle,
        String courseState,
        /** Spec §3: {@code SUGGEST} (recommend, one-tap Accept) or {@code AUTO_ASSIGN}. */
        String mode
) {}
