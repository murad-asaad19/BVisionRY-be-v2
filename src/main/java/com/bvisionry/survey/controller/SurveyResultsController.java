package com.bvisionry.survey.controller;

import com.bvisionry.common.excel.XlsxResponse;
import com.bvisionry.survey.dto.SurveyResponseDetailDto;
import com.bvisionry.survey.dto.SurveyResponsePageDto;
import com.bvisionry.survey.dto.SurveyResultsSummaryDto;
import com.bvisionry.survey.service.SurveyResultsService;
import com.bvisionry.survey.service.SurveyService;
import lombok.RequiredArgsConstructor;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

@RestController
@RequestMapping("/api/surveys/{surveyId}/results")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class SurveyResultsController {

    private final SurveyResultsService resultsService;
    private final SurveyService surveyService;

    @GetMapping("/summary")
    public ResponseEntity<SurveyResultsSummaryDto> summary(@PathVariable UUID surveyId) {
        return ResponseEntity.ok(resultsService.getSummary(surveyId));
    }

    /**
     * Per-section / per-question aggregate for the "Live" analytics tab —
     * restricted to sections opted into live analytics. Polled while responses
     * come in. Same shape as {@code /summary}, filtered.
     */
    @GetMapping("/live")
    public ResponseEntity<SurveyResultsSummaryDto> live(@PathVariable UUID surveyId) {
        return ResponseEntity.ok(resultsService.getLive(surveyId));
    }

    @GetMapping("/responses")
    public ResponseEntity<SurveyResponsePageDto> listResponses(
            @PathVariable UUID surveyId,
            @RequestParam(required = false) String q,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant from,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) Instant to,
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "50") int size) {
        return ResponseEntity.ok(resultsService.listResponses(surveyId, q, from, to, page, size));
    }

    @GetMapping("/responses/{responseId}")
    public ResponseEntity<SurveyResponseDetailDto> getResponseDetail(
            @PathVariable UUID surveyId,
            @PathVariable UUID responseId) {
        return ResponseEntity.ok(resultsService.getResponseDetail(surveyId, responseId));
    }

    @DeleteMapping("/responses/{responseId}")
    public ResponseEntity<Void> deleteResponse(
            @PathVariable UUID surveyId,
            @PathVariable UUID responseId) {
        resultsService.deleteResponse(surveyId, responseId);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/export.xlsx")
    public ResponseEntity<byte[]> exportXlsx(
            @PathVariable UUID surveyId,
            @RequestParam(defaultValue = "download") String mode) throws IOException {
        surveyService.findOrThrow(surveyId);
        ByteArrayOutputStream buf = new ByteArrayOutputStream();
        resultsService.writeXlsx(surveyId, buf);
        return XlsxResponse.build(buf.toByteArray(),
                "survey-" + surveyId + "-responses.xlsx", mode);
    }
}
