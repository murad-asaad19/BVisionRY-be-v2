package com.bvisionry.survey.dto;

import java.time.Instant;
import java.util.UUID;

/**
 * Compact survey-pairing summary embedded on assignment / member-results
 * payloads. Encodes three states in a single nullable field on the parent:
 *
 * <ul>
 *   <li>parent has {@code survey == null} — pipeline has no survey paired,
 *       admin/member UIs should not render a Survey timeline step or CTA.</li>
 *   <li>parent has {@code survey != null} and {@code responseId == null} —
 *       survey is paired but the member hasn't submitted a response yet
 *       ({@code submittedAt} is also null in this state).</li>
 *   <li>parent has {@code survey != null} and {@code responseId != null} —
 *       member has submitted; both {@code responseId} and
 *       {@code submittedAt} are populated.</li>
 * </ul>
 *
 * Bundling these fields collapses what used to be four flat fields on
 * AssignmentDetailResponse (and a separate boolean+responseId pair on
 * MemberResultsResponse) into one cohesive object.
 */
public record SurveySummary(
        UUID surveyId,
        UUID responseId,
        Instant submittedAt
) {}
