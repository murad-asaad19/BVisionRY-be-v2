package com.bvisionry.coaching.dto;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * One coach grant for the org-admin assignment console. Cohort fields are set
 * on cohort-grain rows, member fields on direct-founder rows — never both. Both
 * groups null is the ORG-WIDE grain (V176): {@code orgWide} names it rather
 * than leaving the client to infer a grain from two absences.
 */
public record CoachAssignmentResponse(
        UUID id,
        UUID coachId,
        String coachName,
        String coachEmail,
        UUID cohortId,
        String cohortName,
        UUID memberId,
        String memberName,
        String memberEmail,
        boolean orgWide,
        OffsetDateTime createdAt) {
}
