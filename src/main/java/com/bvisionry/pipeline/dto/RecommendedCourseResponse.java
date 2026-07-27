package com.bvisionry.pipeline.dto;

import java.util.UUID;

/**
 * One course a founder's own assessment put them on, and the pillar that asked
 * for it — the founder-facing read of the {@code auto_enrolments} ledger
 * ("Results and dashboard surface recommended/assigned modules with the reason").
 *
 * <h2>ONE reason, never THE reason</h2>
 * The ledger's idempotency key is {@code (founder, course, evaluation)}, so a
 * course that TWO pillars both ask for is recorded once, against whichever pillar
 * came first in display order. That was ruled compliant with
 * {@code auto_enrolment_explains_why} on the condition that the founder-facing
 * copy reads <em>"recommended because of &lt;pillar&gt;"</em> — one reason among
 * possibly several. {@link #pillarName} is therefore A true reason, never the
 * exhaustive set: copy that renders it as the only reason, or enumerates reasons
 * as complete, is false and outside that ruling.
 *
 * <p>Only {@code outcome = ENROLLED} rows reach here. {@code ALREADY_ENROLLED}
 * means the founder was on the course before this evaluation ran (they picked it
 * themselves, or an earlier assessment did) and {@code COURSE_NOT_PUBLISHED}
 * enrolled nobody — neither is something the engine gave them, so neither is a
 * recommendation to show.
 *
 * @param courseId        the course, as the catalog knows it
 * @param courseTitle     what it is called TODAY, not when the founder was enrolled
 * @param courseSlug      the player link — {@code /app/courses/{slug}/learn}
 * @param coursePublished whether the course is still {@code PUBLISHED}. It was
 *                        when the engine enrolled them (a refusal writes
 *                        {@code COURSE_NOT_PUBLISHED} instead), so {@code false}
 *                        means it was unpublished afterwards. The enrolment — and
 *                        the player — still work: the learn view is gated on
 *                        being enrolled, not on the course's state. The surface
 *                        keeps the link and says the course left the catalog.
 * @param pillarName      the pillar whose band asked for this course, as it is
 *                        named today. ONE reason; see above.
 * @param submissionId    the assessment that decided it, so a results page can
 *                        show what THAT assessment recommended
 */
public record RecommendedCourseResponse(
        UUID courseId,
        String courseTitle,
        String courseSlug,
        boolean coursePublished,
        String pillarName,
        UUID submissionId) {
}
