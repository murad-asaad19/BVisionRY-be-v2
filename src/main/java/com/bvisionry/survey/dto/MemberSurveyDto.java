package com.bvisionry.survey.dto;

import java.util.List;
import java.util.UUID;

/**
 * Member-facing survey payload used by the authenticated post-assessment
 * survey flow. Mirrors {@link PublicSurveyDto} minus the
 * {@code respondentEmailMode}/{@code respondentNameMode} fields — the auth
 * path always derives the respondent identity from the current user, so
 * exposing those modes to the client is dead weight that risks leaking the
 * field-mode policy intended only for anonymous public submissions.
 */
public record MemberSurveyDto(
        UUID id,
        String name,
        String description,
        List<PublicSurveyPillarDto> pillars
) {}
