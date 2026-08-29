package com.bvisionry.survey.service;

import com.bvisionry.common.surveylink.PublicSurveyLink;
import com.bvisionry.common.surveylink.PublicSurveyLinkPort;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.entity.SurveyVisibility;
import com.bvisionry.survey.repository.SurveyRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Optional;
import java.util.UUID;

/**
 * The survey side of {@link PublicSurveyLinkPort}: the one place that decides
 * whether a paired survey is offerable to an anonymous visitor.
 *
 * <p>It lives here rather than in the features that pair one because the answer
 * is made of survey rules — status, visibility, whether a token was ever minted.
 * A feature holding its own copy of that predicate would drift the day the rules
 * change, and would have to import {@code survey} to write it at all.
 */
@Service
@RequiredArgsConstructor
public class PublicSurveyLinkService implements PublicSurveyLinkPort {

    private final SurveyRepository surveyRepository;

    @Override
    @Transactional(readOnly = true)
    public boolean exists(UUID surveyId) {
        return surveyId != null && surveyRepository.existsById(surveyId);
    }

    @Override
    @Transactional(readOnly = true)
    public Optional<PublicSurveyLink> publicLink(UUID surveyId) {
        if (surveyId == null) {
            return Optional.empty();
        }
        return surveyRepository.findById(surveyId)
                .filter(s -> s.getStatus() == SurveyStatus.PUBLISHED)
                .filter(s -> s.getVisibility() == SurveyVisibility.PUBLIC)
                .filter(s -> s.getPublicToken() != null)
                .map(s -> new PublicSurveyLink(s.getPublicToken(), s.getName()));
    }
}
