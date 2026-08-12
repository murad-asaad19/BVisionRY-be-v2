package com.bvisionry.survey.service;

import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.entity.User;

import java.util.UUID;

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
 *   <li>{@link WorkshopIntro} — authenticated pre-workshop intro survey,
 *       bound to a workshop by id; identity comes from the resolved
 *       {@link User}, same as {@link Member}.</li>
 *   <li>{@link ProgramTask} — authenticated SURVEY journey task (redesign
 *       spec §2.1, phase D2); keyed on (task, member) since V173, matching the
 *       journey's done-detection, so two cohorts sharing a survey each get
 *       their own answer. Carries plain identity values, not the
 *       {@code auth} entity — the architecture ratchet forbids NEW
 *       cross-feature type dependencies.</li>
 * </ul>
 *
 * Sealing the type lets {@code persistResponse} branch with an exhaustive
 * {@code switch}, replacing the prior 10-arg method that interleaved nullable
 * fields from both flows.
 */
public sealed interface ResponseContext
        permits ResponseContext.Public, ResponseContext.Member,
                ResponseContext.WorkshopIntro, ResponseContext.ProgramTask {

    record Public(String email, String name, String ipHash, String cookieId,
                  UUID giftToken) implements ResponseContext {}

    record Member(Submission submission, User user) implements ResponseContext {}

    record WorkshopIntro(UUID workshopId, User user) implements ResponseContext {}

    record ProgramTask(UUID taskId, UUID userId, String email, String name)
            implements ResponseContext {}
}
