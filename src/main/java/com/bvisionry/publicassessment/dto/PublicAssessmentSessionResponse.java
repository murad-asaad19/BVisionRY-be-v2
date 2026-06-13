package com.bvisionry.publicassessment.dto;

import java.util.UUID;

/**
 * Minted anonymous session. The {@code accessToken} is the respondent's
 * per-submission credential for the rest of the public taker flow — the client
 * keeps it (localStorage) and presents it on every session-scoped call.
 */
public record PublicAssessmentSessionResponse(
        UUID accessToken,
        UUID submissionId
) {}
