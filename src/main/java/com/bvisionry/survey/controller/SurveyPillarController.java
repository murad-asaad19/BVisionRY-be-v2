package com.bvisionry.survey.controller;

import com.bvisionry.survey.dto.SurveyPillarDto;
import com.bvisionry.survey.dto.SurveyPillarRequest;
import com.bvisionry.survey.dto.SurveyReorderRequest;
import com.bvisionry.survey.service.SurveyPillarService;
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
@RequestMapping("/api/surveys/{surveyId}/pillars")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class SurveyPillarController {

    private final SurveyPillarService pillarService;

    @PostMapping
    public ResponseEntity<SurveyPillarDto> create(
            @PathVariable UUID surveyId,
            @Valid @RequestBody SurveyPillarRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(pillarService.create(surveyId, request));
    }

    @PutMapping("/{id}")
    public ResponseEntity<SurveyPillarDto> update(
            @PathVariable UUID surveyId,
            @PathVariable UUID id,
            @Valid @RequestBody SurveyPillarRequest request) {
        return ResponseEntity.ok(pillarService.update(surveyId, id, request));
    }

    @PostMapping("/{id}/duplicate")
    public ResponseEntity<SurveyPillarDto> duplicate(
            @PathVariable UUID surveyId,
            @PathVariable UUID id) {
        return ResponseEntity.status(HttpStatus.CREATED)
                .body(pillarService.duplicate(surveyId, id));
    }

    @DeleteMapping("/{id}")
    public ResponseEntity<Void> delete(
            @PathVariable UUID surveyId,
            @PathVariable UUID id) {
        pillarService.delete(surveyId, id);
        return ResponseEntity.noContent().build();
    }

    @PatchMapping("/reorder")
    public ResponseEntity<Void> reorder(
            @PathVariable UUID surveyId,
            @Valid @RequestBody SurveyReorderRequest request) {
        pillarService.reorder(surveyId, request);
        return ResponseEntity.noContent().build();
    }
}
