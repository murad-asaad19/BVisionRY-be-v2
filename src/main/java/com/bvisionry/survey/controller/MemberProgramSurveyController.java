package com.bvisionry.survey.controller;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.survey.dto.MemberSurveyDto;
import com.bvisionry.survey.dto.SurveySubmitRequest;
import com.bvisionry.survey.dto.SurveySubmitResponseDto;
import com.bvisionry.survey.service.ProgramTaskSurveyService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Authenticated survey endpoints for SURVEY journey tasks (redesign spec §2.1,
 * phase D2): the member takes the task's paired survey in-app, and the
 * response IS the completion — the journey's done-detection keys on
 * (task, member). The caller must be enrolled in the task's cohort and in
 * its module's audience; one submission per member per TASK. Lives in the
 * survey slice so it can reuse the shared response-persistence + validation,
 * mirroring {@link MemberWorkshopSurveyController}.
 */
@RestController
@RequestMapping("/api/my/program/tasks/{taskId}/survey")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MemberProgramSurveyController {

    private final ProgramTaskSurveyService programTaskSurveys;
    // common.security, not auth.SecurityUtils: the architecture ratchet
    // forbids NEW survey→auth dependencies.
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public ResponseEntity<MemberSurveyDto> get(@PathVariable UUID taskId) {
        return ResponseEntity.ok(
                programTaskSurveys.getForProgramTask(taskId, currentUser.require().userId()));
    }

    @PostMapping
    public ResponseEntity<SurveySubmitResponseDto> submit(
            @PathVariable UUID taskId,
            @Valid @RequestBody SurveySubmitRequest body,
            HttpServletRequest request) {
        SurveySubmitResponseDto result = programTaskSurveys.submitForProgramTask(
                taskId,
                currentUser.require().userId(),
                body,
                request.getHeader("User-Agent"));
        return ResponseEntity.ok(result);
    }
}
