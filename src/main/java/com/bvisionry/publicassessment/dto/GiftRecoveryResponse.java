package com.bvisionry.publicassessment.dto;

import com.bvisionry.common.enums.SubmissionStatus;

import java.util.UUID;

/**
 * Result of resolving a survey-gift token back to the respondent's bound
 * assessment submission, so the public taker can route a reopened personalized
 * link: show results when EVALUATED, offer a retake when failed, or resume.
 *
 * <p>The endpoint returns 204 (no body) when the gift token has no started
 * submission yet — the frontend then falls through to the normal intro/start flow.
 *
 * @param accessToken the bound submission's session credential, used for all
 *                    subsequent {@code /sessions/{accessToken}} calls
 * @param status      the bound submission's current status — the taker derives
 *                    routing (results / retake / resume) from this plus the link
 *                    info it already holds, so no redundant visibility flags are
 *                    sent (they would only duplicate, and risk disagreeing with,
 *                    the authoritative client-side derivation)
 */
public record GiftRecoveryResponse(
        UUID accessToken,
        SubmissionStatus status
) {}
