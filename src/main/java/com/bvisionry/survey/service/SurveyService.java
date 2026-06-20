package com.bvisionry.survey.service;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.survey.dto.SurveyCreateRequest;
import com.bvisionry.survey.dto.SurveyDto;
import com.bvisionry.survey.dto.SurveyMetadataUpdateRequest;
import com.bvisionry.survey.dto.SurveyStatusRequest;
import com.bvisionry.survey.dto.SurveySummaryDto;
import com.bvisionry.survey.dto.SurveyUpdateRequest;
import com.bvisionry.survey.entity.RespondentFieldMode;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyPillar;
import com.bvisionry.survey.entity.SurveyQuestion;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.entity.SurveyVisibility;
import com.bvisionry.survey.repository.SurveyRepository;
import com.bvisionry.survey.repository.SurveyResponseRepository;
import com.bvisionry.publicassessment.repository.PublicAssessmentLinkRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class SurveyService {

    private final SurveyRepository surveyRepository;
    private final SurveyResponseRepository responseRepository;
    private final PublicAssessmentLinkRepository publicAssessmentLinkRepository;
    private final SurveyMapper mapper;

    @Transactional
    public SurveyDto create(SurveyCreateRequest request, UUID createdBy) {
        Survey survey = new Survey();
        survey.setName(request.name());
        survey.setDescription(request.description());
        survey.setStatus(SurveyStatus.DRAFT);
        survey.setVisibility(
                request.visibility() != null
                        ? request.visibility()
                        : SurveyVisibility.PRIVATE);
        survey.setRespondentEmailMode(
                request.respondentEmailMode() != null
                        ? request.respondentEmailMode()
                        : RespondentFieldMode.NONE);
        survey.setRespondentNameMode(
                request.respondentNameMode() != null
                        ? request.respondentNameMode()
                        : RespondentFieldMode.NONE);
        survey.setCreatedBy(createdBy);
        Survey saved = surveyRepository.save(survey);
        return mapper.toSurveyDto(saved);
    }

    @Transactional(readOnly = true)
    public List<SurveySummaryDto> list(SurveyStatus status) {
        List<Survey> surveys = status == null
                ? surveyRepository.findAllByOrderByUpdatedAtDesc()
                : surveyRepository.findByStatusOrderByUpdatedAtDesc(status);

        Map<UUID, Long> responseCounts = new HashMap<>();
        if (!surveys.isEmpty()) {
            List<UUID> ids = surveys.stream().map(Survey::getId).toList();
            for (Object[] row : responseRepository.countBySurveyIdIn(ids)) {
                responseCounts.put((UUID) row[0], (Long) row[1]);
            }
        }

        return surveys.stream().map(s -> new SurveySummaryDto(
                s.getId(),
                s.getName(),
                s.getDescription(),
                s.getStatus(),
                s.getVisibility(),
                s.getPublicToken(),
                s.getPublishedAt(),
                s.getClosedAt(),
                s.getPillars() != null ? s.getPillars().size() : 0,
                responseCounts.getOrDefault(s.getId(), 0L),
                s.getCreatedAt(),
                s.getUpdatedAt()
        )).toList();
    }

    @Transactional(readOnly = true)
    public SurveyDto getById(UUID id) {
        return mapper.toSurveyDto(findOrThrow(id));
    }

    @Transactional
    public SurveyDto update(UUID id, SurveyUpdateRequest request) {
        Survey survey = findOrThrow(id);
        requireEditable(survey);
        survey.setName(request.name());
        survey.setDescription(request.description());
        survey.setRespondentEmailMode(request.respondentEmailMode());
        survey.setRespondentNameMode(request.respondentNameMode());
        applyVisibilityChange(survey, request.visibility());
        reconcileGiftWithEmailMode(survey);
        return mapper.toSurveyDto(surveyRepository.save(survey));
    }

    /**
     * Updates only the human-readable metadata (name, description). Allowed in any
     * status because it doesn't change the survey's structure or response schema —
     * useful for fixing typos or rebranding a published or closed survey.
     *
     * <p>Visibility is also editable through this endpoint so admins can flip a
     * PUBLISHED survey between PRIVATE and PUBLIC without unpublishing it. The
     * public-token side effect (mint on flip-to-PUBLIC, clear on flip-to-PRIVATE)
     * is handled by {@link #applyVisibilityChange}.
     */
    @Transactional
    public SurveyDto updateMetadata(UUID id, SurveyMetadataUpdateRequest request) {
        Survey survey = findOrThrow(id);
        survey.setName(request.name());
        survey.setDescription(request.description());
        if (request.visibility() != null) {
            applyVisibilityChange(survey, request.visibility());
        }
        if (request.respondentEmailMode() != null) {
            survey.setRespondentEmailMode(request.respondentEmailMode());
        }
        if (request.respondentNameMode() != null) {
            survey.setRespondentNameMode(request.respondentNameMode());
        }
        applyGiftChange(survey, request.giftPublicAssessmentLinkId(),
                request.clearGiftPublicAssessmentLink());
        reconcileGiftWithEmailMode(survey);
        return mapper.toSurveyDto(surveyRepository.save(survey));
    }

    /**
     * Enforces the "a gift requires collecting an email" invariant from whichever
     * write path runs: if email collection ends up NONE, any configured gift is
     * dropped (it could never be delivered). {@link #applyGiftChange} rejects the
     * explicit "set a gift while email is NONE" case up front; this catches the
     * other direction — turning email off while a gift is already attached —
     * including the full {@code update()} (PUT) path which doesn't touch the gift.
     */
    private void reconcileGiftWithEmailMode(Survey survey) {
        if (survey.getRespondentEmailMode() == RespondentFieldMode.NONE) {
            survey.setGiftPublicAssessmentLinkId(null);
        }
    }

    /**
     * Sets, keeps, or clears the gifted public assessment. A bare null is
     * ambiguous (keep vs remove), so removal is driven by an explicit
     * {@code clear} flag; a non-null id sets the gift, and null + no clear
     * leaves the current value untouched (so partial metadata updates — e.g.
     * the visibility quick-toggle — don't wipe a configured gift).
     *
     * <p>A gift is only deliverable if the survey collects an email, so setting
     * one while {@code respondentEmailMode} is NONE is rejected. The email mode
     * has already been applied above, so this reads the effective value.
     */
    private void applyGiftChange(Survey survey, UUID giftLinkId, boolean clear) {
        if (clear) {
            survey.setGiftPublicAssessmentLinkId(null);
            return;
        }
        if (giftLinkId == null) return;
        if (!publicAssessmentLinkRepository.existsById(giftLinkId)) {
            throw new ResourceNotFoundException("Public assessment", giftLinkId.toString());
        }
        if (survey.getRespondentEmailMode() == RespondentFieldMode.NONE) {
            throw new BadRequestException(
                    "Enable respondent email collection before gifting an assessment — "
                    + "the gift is emailed to the address the respondent provides.");
        }
        survey.setGiftPublicAssessmentLinkId(giftLinkId);
    }

    /**
     * Applies a visibility change while keeping the {@code publicToken} consistent:
     * PUBLISHED + PUBLIC must always have a token; everything else must have none.
     *
     * <ul>
     *   <li>PRIVATE → PUBLIC on a PUBLISHED survey: mint a fresh token.</li>
     *   <li>any → PRIVATE: clear the token, regardless of status. CLOSED surveys
     *       can carry a dormant token from a prior PUBLISHED+PUBLIC state and
     *       {@code loadPubliclyReachableOrThrow} already 410s them, but clearing
     *       defends against any future endpoint that reads the token directly.</li>
     *   <li>DRAFT-side mints are deferred to the publish path so a never-published
     *       survey doesn't accumulate a usable token.</li>
     * </ul>
     */
    private void applyVisibilityChange(Survey survey, SurveyVisibility next) {
        if (next == null) return;
        SurveyVisibility current = survey.getVisibility();
        survey.setVisibility(next);
        if (current == next) return;
        if (next == SurveyVisibility.PRIVATE) {
            survey.setPublicToken(null);
        } else if (next == SurveyVisibility.PUBLIC
                && survey.getStatus() == SurveyStatus.PUBLISHED
                && survey.getPublicToken() == null) {
            survey.setPublicToken(UUID.randomUUID());
        }
    }

    /**
     * Clones a survey along with its pillars and questions. The duplicate starts as
     * DRAFT, has its own (null) public token until published, and the name is suffixed
     * " (Copy)" to keep listings unambiguous.
     */
    @Transactional
    public SurveyDto duplicate(UUID id, UUID createdBy) {
        Survey original = surveyRepository.findByIdWithPillars(id)
                .orElseThrow(() -> new ResourceNotFoundException("Survey", id.toString()));

        Survey cloned = new Survey();
        cloned.setName(original.getName() + " (Copy)");
        cloned.setDescription(original.getDescription());
        cloned.setStatus(SurveyStatus.DRAFT);
        cloned.setVisibility(original.getVisibility());
        cloned.setRespondentEmailMode(original.getRespondentEmailMode());
        cloned.setRespondentNameMode(original.getRespondentNameMode());
        cloned.setCreatedBy(createdBy);

        List<SurveyPillar> clonedPillars = new ArrayList<>();
        for (SurveyPillar originalPillar : original.getPillars()) {
            clonedPillars.add(clonePillar(originalPillar, cloned));
        }
        cloned.setPillars(clonedPillars);

        return mapper.toSurveyDto(surveyRepository.save(cloned));
    }

    private SurveyPillar clonePillar(SurveyPillar original, Survey targetSurvey) {
        SurveyPillar cloned = new SurveyPillar();
        cloned.setSurvey(targetSurvey);
        cloned.setName(original.getName());
        cloned.setDescription(original.getDescription());
        cloned.setDisplayOrder(original.getDisplayOrder());

        List<SurveyQuestion> clonedQuestions = new ArrayList<>();
        for (SurveyQuestion originalQuestion : original.getQuestions()) {
            SurveyQuestion clonedQuestion = new SurveyQuestion();
            clonedQuestion.setPillar(cloned);
            clonedQuestion.setType(originalQuestion.getType());
            clonedQuestion.setPromptText(originalQuestion.getPromptText());
            clonedQuestion.setDisplayOrder(originalQuestion.getDisplayOrder());
            clonedQuestion.setRequired(originalQuestion.isRequired());
            if (originalQuestion.getConfigJson() != null) {
                clonedQuestion.setConfigJson(new LinkedHashMap<>(originalQuestion.getConfigJson()));
            }
            clonedQuestions.add(clonedQuestion);
        }
        cloned.setQuestions(clonedQuestions);
        return cloned;
    }

    @Transactional
    public SurveyDto transitionStatus(UUID id, SurveyStatusRequest request) {
        Survey survey = findOrThrow(id);
        SurveyStatus current = survey.getStatus();
        SurveyStatus target = request.status();

        validateTransition(survey, current, target);

        switch (target) {
            case PUBLISHED -> {
                if (current == SurveyStatus.DRAFT) {
                    validatePublishReadiness(survey);
                    // Only mint a token for PUBLIC surveys — PRIVATE surveys are
                    // reachable only through the authenticated post-completion path,
                    // so a token would be dead weight (and a potential leak vector).
                    if (survey.getVisibility() == SurveyVisibility.PUBLIC) {
                        survey.setPublicToken(UUID.randomUUID());
                    } else {
                        survey.setPublicToken(null);
                    }
                    survey.setPublishedAt(Instant.now());
                }
                survey.setClosedAt(null);
            }
            case CLOSED -> survey.setClosedAt(Instant.now());
            case DRAFT -> {
                if (responseRepository.countBySurveyId(id) > 0) {
                    throw new IllegalOperationException(
                            "Cannot revert to DRAFT: survey has existing responses. Duplicate it instead.");
                }
                survey.setPublicToken(null);
                survey.setPublishedAt(null);
                survey.setClosedAt(null);
            }
        }

        survey.setStatus(target);
        return mapper.toSurveyDto(surveyRepository.save(survey));
    }

    @Transactional
    public void delete(UUID id) {
        Survey survey = findOrThrow(id);
        if (survey.getStatus() == SurveyStatus.PUBLISHED) {
            throw new IllegalOperationException(
                    "Cannot delete a PUBLISHED survey. Unpublish it first.");
        }
        surveyRepository.delete(survey);
    }

    public Survey findOrThrow(UUID id) {
        return surveyRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Survey", id.toString()));
    }

    /**
     * Surveys are editable in DRAFT and CLOSED — CLOSED is treated as a paused
     * draft, so admins can fix wording or restructure before reopening. PUBLISHED
     * is the only locked state, since active respondents would otherwise see the
     * structure shift mid-flight. Editing a CLOSED survey with existing responses
     * may leave answers referencing removed/renamed questions; that's accepted by
     * design.
     */
    public void requireEditable(Survey survey) {
        if (survey.getStatus() == SurveyStatus.PUBLISHED) {
            throw new IllegalOperationException(
                    "Cannot modify a PUBLISHED survey. Unpublish it first.");
        }
    }

    private void validateTransition(Survey survey, SurveyStatus current, SurveyStatus target) {
        if (current == target) {
            throw new BadRequestException("Survey is already " + current);
        }
        boolean valid = switch (current) {
            case DRAFT -> target == SurveyStatus.PUBLISHED;
            case PUBLISHED -> target == SurveyStatus.CLOSED || target == SurveyStatus.DRAFT;
            case CLOSED -> target == SurveyStatus.PUBLISHED;
        };
        if (!valid) {
            throw new IllegalOperationException(
                    "Invalid status transition from " + current + " to " + target);
        }
    }

    private void validatePublishReadiness(Survey survey) {
        if (survey.getPillars() == null || survey.getPillars().isEmpty()) {
            throw new BadRequestException(
                    "Cannot publish survey: must have at least 1 pillar");
        }
        boolean anyQuestion = survey.getPillars().stream()
                .anyMatch(p -> p.getQuestions() != null && !p.getQuestions().isEmpty());
        if (!anyQuestion) {
            throw new BadRequestException(
                    "Cannot publish survey: at least one pillar must have at least one question");
        }
    }
}
