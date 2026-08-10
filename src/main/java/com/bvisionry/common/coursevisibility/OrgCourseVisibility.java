package com.bvisionry.common.coursevisibility;

/**
 * Redesign spec §3: who, in the ORG world, may see and assign a course. The
 * super admin sets it per course; org admins and members only inherit.
 *
 * <p>Deliberately NOT the pre-existing {@code course.visibility}
 * (PUBLIC/UNLISTED/PRIVATE/MEMBERS) or {@code course.access} — those are the
 * public marketing catalog's own knobs and say nothing about organizations.
 * Column: {@code course.org_visibility} (V168).
 */
public enum OrgCourseVisibility {

    /** Every organization. The default every pre-V168 course keeps. */
    EVERYONE,

    /** Organizations on {@code course.org_visibility_min_tier} or above. */
    MIN_TIER,

    /** Exactly the organizations listed in {@code course_visible_orgs}. */
    ORG_LIST
}
