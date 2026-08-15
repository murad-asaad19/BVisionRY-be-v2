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
 * Authenticated, submission-scoped post-assessment survey endpoints. The
 * caller must be the user the assessment was assigned to. Replaces the
 * public token flow for any survey paired to a pipeline.
 */
@RestController
@RequestMapping("/api/my/assessments/{submissionId}/post-completion-survey")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
public class MemberSurveyController {

    private final CurrentUserAccessor currentUser;
    private final SurveyResponseService responseService;

    @GetMapping
    public ResponseEntity<MemberSurveyDto> get(@PathVariable UUID submissionId) {
        return ResponseEntity.ok(
                responseService.getForSubmission(submissionId, currentUser.require().userId()));
    }

    @PostMapping
    public ResponseEntity<SurveySubmitResponseDto> submit(
            @PathVariable UUID submissionId,
            @Valid @RequestBody SurveySubmitRequest body,
            HttpServletRequest request) {
        SurveySubmitResponseDto result = responseService.submitForSubmission(
                submissionId,
                currentUser.require().userId(),
                body,
                request.getHeader("User-Agent"));
        return ResponseEntity.ok(result);
    }
}
