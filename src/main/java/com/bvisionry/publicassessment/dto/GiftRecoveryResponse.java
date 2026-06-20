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
 * @param status      the bound submission's current status
 * @param showResults whether results are viewable now (link allows it AND status is EVALUATED)
 * @param retakeable  whether the respondent may retake (status is FAILED or NEEDS_REVIEW)
 */
public record GiftRecoveryResponse(
        UUID accessToken,
        SubmissionStatus status,
        boolean showResults,
        boolean retakeable
) {}
