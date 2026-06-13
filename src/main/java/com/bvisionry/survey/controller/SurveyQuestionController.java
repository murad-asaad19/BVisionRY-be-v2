package com.bvisionry.survey.controller;

import com.bvisionry.survey.dto.SurveyQuestionDto;
import com.bvisionry.survey.dto.SurveyQuestionRequest;
import com.bvisionry.survey.dto.SurveyReorderRequest;
import com.bvisionry.survey.service.SurveyQuestionService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

@RestController
@RequestMapping("/api/surveys/{surveyId}/pillars/{pillarId}/questions")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class SurveyQuestionController {

    private final SurveyQuestionService questionService;

    @PostMapping
    public ResponseEntity<SurveyQuestionDto> create(
            @PathVariable UUID surveyId,
            @PathVariable UUID pillarId,
            @Valid @RequestBody SurveyQuestionRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.create(surveyId, pillarId, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SurveyQuestionDto> update(
            @PathVariable UUID surveyId,
            @PathVariable UUID pillarId,
            @PathVariable UUID id,
            @Valid @RequestBody SurveyQuestionRequest request) {
        return ResponseEntity.ok(questionService.update(surveyId, pillarId, id, request));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<SurveyQuestionDto> duplicate(
            @PathVariable UUID surveyId,
            @PathVariable UUID pillarId,
            @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(questionService.duplicate(surveyId, pillarId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID surveyId,
            @PathVariable UUID pillarId,
            @PathVariable UUID id) {
        questionService.delete(surveyId, pillarId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/reorder")
    public ResponseEntity<Void> reorder(
            @PathVariable UUID surveyId,
            @PathVariable UUID pillarId,
            @Valid @RequestBody SurveyReorderRequest request) {
        questionService.reorder(surveyId, pillarId, request);
        return ResponseEntity.noContent().build();
    }
}
