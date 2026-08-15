package com.bvisionry.survey.controller;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.survey.dto.MemberSurveyDto;
import com.bvisionry.survey.dto.SurveySubmitRequest;
import com.bvisionry.survey.dto.SurveySubmitResponseDto;
import com.bvisionry.survey.service.SurveyResponseService;
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
 * Authenticated, workshop-scoped survey endpoints: the pre-workshop (intro)
 * gate and inline SURVEY pipeline tasks. The caller must be enrolled in the
 * workshop (a member of one of its teams); each survey allows one submission
 * per member per workshop. Lives in the survey slice so it can reuse the
 * shared response-persistence logic, mirroring {@link MemberSurveyController}.
 */
@RestController
@RequestMapping("/api/my/workshops/{workshopId}")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MemberWorkshopSurveyController {

    private final CurrentUserAccessor currentUser;
    private final SurveyResponseService responseService;

    @GetMapping("/pre-survey")
    public ResponseEntity<MemberSurveyDto> get(@PathVariable UUID workshopId) {
        return ResponseEntity.ok(
                responseService.getForWorkshop(workshopId, currentUser.require().userId()));
    }

    @PostMapping("/pre-survey")
    public ResponseEntity<SurveySubmitResponseDto> submit(
            @PathVariable UUID workshopId,
            @Valid @RequestBody SurveySubmitRequest body,
            HttpServletRequest request) {
        SurveySubmitResponseDto result = responseService.submitForWorkshop(
                workshopId,
                currentUser.require().userId(),
                body,
                request.getHeader("User-Agent"));
        return ResponseEntity.ok(result);
    }

    @GetMapping("/tasks/{taskId}/survey")
    public ResponseEntity<MemberSurveyDto> getTaskSurvey(@PathVariable UUID workshopId,
                                                         @PathVariable UUID taskId) {
        return ResponseEntity.ok(responseService.getForWorkshopTask(
                workshopId, taskId, currentUser.require().userId()));
    }

    @PostMapping("/tasks/{taskId}/survey")
    public ResponseEntity<SurveySubmitResponseDto> submitTaskSurvey(
            @PathVariable UUID workshopId,
            @PathVariable UUID taskId,
            @Valid @RequestBody SurveySubmitRequest body,
            HttpServletRequest request) {
        SurveySubmitResponseDto result = responseService.submitForWorkshopTask(
                workshopId,
                taskId,
                currentUser.require().userId(),
                body,
                request.getHeader("User-Agent"));
        return ResponseEntity.ok(result);
    }
}
