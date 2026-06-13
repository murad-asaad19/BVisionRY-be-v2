package com.bvisionry.survey.controller;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.survey.dto.SurveyCreateRequest;
import com.bvisionry.survey.dto.SurveyDto;
import com.bvisionry.survey.dto.SurveyMetadataUpdateRequest;
import com.bvisionry.survey.dto.SurveyStatusRequest;
import com.bvisionry.survey.dto.SurveySummaryDto;
import com.bvisionry.survey.dto.SurveyUpdateRequest;
import com.bvisionry.survey.entity.SurveyStatus;
import com.bvisionry.survey.service.SurveyService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;
import java.util.UUID;

@RestController
@RequestMapping("/api/surveys")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class SurveyController {

    private final SurveyService surveyService;

    @PostMapping
    public ResponseEntity<SurveyDto> create(@Valid @RequestBody SurveyCreateRequest request) {
        SurveyDto created = surveyService.create(request, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping
    public ResponseEntity<List<SurveySummaryDto>> list(@RequestParam(required = false) SurveyStatus status) {
        return ResponseEntity.ok(surveyService.list(status));
    }

    @GetMapping("/{id}")
    public ResponseEntity<SurveyDto> getById(@PathVariable UUID id) {
        return ResponseEntity.ok(surveyService.getById(id));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SurveyDto> update(
            @PathVariable UUID id,
            @Valid @RequestBody SurveyUpdateRequest request) {
        return ResponseEntity.ok(surveyService.update(id, request));
    }

    @PatchMapping("/{id}/metadata")
    public ResponseEntity<SurveyDto> updateMetadata(
            @PathVariable UUID id,
            @Valid @RequestBody SurveyMetadataUpdateRequest request) {
        return ResponseEntity.ok(surveyService.updateMetadata(id, request));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<SurveyDto> duplicate(@PathVariable UUID id) {
        SurveyDto duplicated = surveyService.duplicate(id, SecurityUtils.getCurrentUserId());
        return ResponseEntity.status(HttpStatus.CREATED).body(duplicated);
    }

    @PatchMapping("/{id}/status")
    public ResponseEntity<SurveyDto> transitionStatus(
            @PathVariable UUID id,
            @Valid @RequestBody SurveyStatusRequest request) {
        return ResponseEntity.ok(surveyService.transitionStatus(id, request));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        surveyService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
