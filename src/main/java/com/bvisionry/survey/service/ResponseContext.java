package com.bvisionry.survey.service;

import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.entity.User;

/**
 * Sealed identity-and-channel context for a survey submission. The two
 * variants correspond to the two ingress paths into
 * {@link SurveyResponseService#persistResponse}:
 *
 * <ul>
 *   <li>{@link Public} — anonymous (or self-identified) submission via the
 *       public token; carries respondent-supplied email/name and the
 *       request-scoped abuse-mitigation hashes.</li>
 *   <li>{@link Member} — authenticated submission bound to an assessment
 *       {@link Submission}; identity is taken from the resolved
 *       {@link User} so the request body can never override it.</li>
 * </ul>
 *
 * Sealing the type lets {@code persistResponse} branch with an exhaustive
 * {@code switch}, replacing the prior 10-arg method that interleaved nullable
 * fields from both flows.
 */
public sealed interface ResponseContext permits ResponseContext.Public, ResponseContext.Member {

    record Public(String email, String name, String ipHash, String cookieId) implements ResponseContext {}

    record Member(Submission submission, User user) implements ResponseContext {}
}
