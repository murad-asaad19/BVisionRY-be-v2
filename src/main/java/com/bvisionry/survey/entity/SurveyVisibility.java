package com.bvisionry.survey.entity;

/**
 * Controls how a survey can be reached.
 *
 * <ul>
 *   <li>{@link #PRIVATE}: only reachable through the authenticated, submission-scoped
 *       member path (e.g. paired post-completion surveys). The public-by-token endpoint
 *       always 404s for these surveys, even if a stale {@code publicToken} exists.</li>
 *   <li>{@link #PUBLIC}: reachable both through the authenticated path and via the
 *       public {@code /s/{token}} link. A {@code publicToken} is minted on publish.</li>
 * </ul>
 *
 * <p>New surveys default to {@link #PRIVATE}. Existing rows pre-V56 were bulk-set to
 * {@link #PUBLIC} in the migration to preserve current behavior.
 */
public enum SurveyVisibility {
    PRIVATE,
    PUBLIC
}
