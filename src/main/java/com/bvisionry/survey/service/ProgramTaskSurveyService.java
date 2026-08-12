package com.bvisionry.survey.service;

import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.survey.dto.MemberSurveyDto;
import com.bvisionry.survey.dto.SurveySubmitRequest;
import com.bvisionry.survey.dto.SurveySubmitResponseDto;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.repository.ProgramTaskSurveyRepository;
import com.bvisionry.survey.repository.SurveyRepository;

import lombok.RequiredArgsConstructor;

/**
 * SURVEY journey tasks (redesign spec §2.1, phase D2): the member takes the
 * task's paired survey in-app, and the response IS the completion — the
 * journey's done-detection keys on (task, member) since V173, so two cohorts
 * sharing one survey each get their own answer.
 *
 * <p>A separate service (not more methods on {@link SurveyResponseService})
 * on purpose: the architecture freeze pins that class's constructor signature
 * in the frozen-violations store, so this flow keeps its own dependencies and
 * reuses the shared validation/persistence via the package-private
 * {@link SurveyResponseService#persistResponse}.
 */
@Service
@RequiredArgsConstructor
public class ProgramTaskSurveyService {

    private final SurveyRepository surveyRepository;
    private final ProgramTaskSurveyRepository programTasks;
    private final SurveyMapper mapper;
    private final SurveyResponseService responseService;

    /**
     * Resolve the published survey paired to a SURVEY journey task. Same
     * availability contract as the workshop flows: 404 when the caller isn't
     * in the task's cohort/audience, the task isn't LIVE, or no survey is
     * paired — all indistinguishable so neither existence nor membership
     * leaks; 410 when the survey is closed; 409 once this member has answered
     * THIS task — the journey's done-detection keys on (task, member) since
     * V173, so the gate must agree with it.
     */
    @Transactional(readOnly = true)
    public MemberSurveyDto getForProgramTask(UUID taskId, UUID currentUserId) {
        ProgramTaskSurveyRepository.ProgramTaskSurveyRow row =
                resolveEnrolledTask(taskId, currentUserId);
        Survey survey = resolvePublishedSurvey(row.surveyId());
        if (programTasks.hasResponse(taskId, currentUserId)) {
            throw new DuplicateResourceException(
                    "Survey response already submitted for this task");
        }
        return mapper.toMemberSurveyDto(survey);
    }

    /**
     * Submit a SURVEY journey task's response. Identity is taken from the
     * current user, never the body; only a LAUNCHED cohort accepts writes —
     * COMPLETED/ARCHIVED are read-only (mirrors the journey's submit gate).
     */
    @Transactional
    public SurveySubmitResponseDto submitForProgramTask(UUID taskId,
                                                        UUID currentUserId,
                                                        SurveySubmitRequest request,
                                                        String userAgent) {
        ProgramTaskSurveyRepository.ProgramTaskSurveyRow row =
                resolveEnrolledTask(taskId, currentUserId);
        // Writes only while LAUNCHED (V167 lifecycle): COMPLETED/ARCHIVED are
        // read-only, DRAFT is invisible to members anyway — mirrors the
        // journey's submit gate.
        if (!"LAUNCHED".equals(row.cohortStatus())) {
            throw new BadRequestException("This cohort is read-only now.");
        }
        Survey survey = resolvePublishedSurvey(row.surveyId());

        if (programTasks.hasResponse(taskId, currentUserId)) {
            throw new DuplicateResourceException(
                    "Survey response already submitted for this task");
        }

        ProgramTaskSurveyRepository.UserIdentityRow identity =
                programTasks.findUserIdentity(currentUserId)
                        .orElseThrow(() -> new ResourceNotFoundException(
                                "User", currentUserId.toString()));

        try {
            return responseService.persistResponse(
                    survey,
                    request.answers(),
                    new ResponseContext.ProgramTask(
                            taskId, currentUserId, identity.email(), identity.name()),
                    userAgent);
        } catch (DataIntegrityViolationException e) {
            // ux_survey_responses_program_task_user is the real race gate.
            throw new DuplicateResourceException(
                    "Survey response already submitted for this task");
        }
    }

    /** The enrolled LIVE SURVEY journey-task row, or 404 (existence/membership hidden). */
    private ProgramTaskSurveyRepository.ProgramTaskSurveyRow resolveEnrolledTask(
            UUID taskId, UUID currentUserId) {
        return programTasks.findEnrolledTaskSurvey(taskId, currentUserId)
                .orElseThrow(() -> new ResourceNotFoundException("Task", taskId.toString()));
    }

    /** Published-or-gone lifecycle for a task-paired survey id (nullable = none paired). */
    private Survey resolvePublishedSurvey(UUID surveyId) {
        if (surveyId == null) {
            throw new ResourceNotFoundException("Survey", "program task (none paired)");
        }
        Survey survey = surveyRepository.findById(surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("Survey", surveyId.toString()));
        if (survey.getStatus() == SurveyStatus.CLOSED) {
            throw new ResponseStatusException(HttpStatus.GONE, "Survey is closed");
        }
        if (survey.getStatus() != SurveyStatus.PUBLISHED) {
            throw new ResourceNotFoundException("Survey", surveyId.toString());
        }
        return survey;
    }
}
