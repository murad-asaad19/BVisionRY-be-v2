package com.bvisionry.aiconfig.controller;

import com.bvisionry.aiconfig.dto.PromptTemplateResponse;
import com.bvisionry.aiconfig.dto.PromptTemplateUpdateRequest;
import com.bvisionry.aiconfig.dto.TryItOutRequest;
import com.bvisionry.aiconfig.dto.TryItOutResponse;
import com.bvisionry.aiconfig.service.PromptTemplateService;
import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.assessment.entity.Answer;
import com.bvisionry.common.enums.PromptType;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.evaluation.EvaluationEngine;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.repository.PillarRepository;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

@RestController
@RequestMapping("/api/ai-config")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class PromptTemplateController {

    private final PromptTemplateService promptService;
    private final EvaluationEngine evaluationEngine;
    private final RateLimitService rateLimitService;
    private final PillarRepository pillarRepository;

    @GetMapping("/prompts")
    public ResponseEntity<List<PromptTemplateResponse>> getAllActivePrompts() {
        return ResponseEntity.ok(promptService.getAllActivePrompts());
    }

    @GetMapping("/prompts/{type}")
    public ResponseEntity<PromptTemplateResponse> getActivePrompt(@PathVariable PromptType type) {
        return ResponseEntity.ok(promptService.getActivePrompt(type));
    }

    @PutMapping("/prompts/{type}")
    public ResponseEntity<PromptTemplateResponse> updatePrompt(
            @PathVariable PromptType type,
            @Valid @RequestBody PromptTemplateUpdateRequest request) {
        return ResponseEntity.ok(promptService.updatePrompt(type, request));
    }

    @PostMapping("/try-it-out")
    public ResponseEntity<TryItOutResponse> tryItOut(@Valid @RequestBody TryItOutRequest request) {
        rateLimitService.checkTryItOutLimit("anonymous");

        Pillar pillar = pillarRepository.findByIdWithQuestions(request.pillarId())
                .orElseThrow(() -> new ResourceNotFoundException("Pillar", request.pillarId().toString()));

        // Build transient Answer entities from the request
        List<Answer> answers = new ArrayList<>();
        for (Question q : pillar.getQuestions()) {
            TryItOutRequest.AnswerInput input = request.answers().get(q.getId().toString());
            if (input == null) continue;
            Answer answer = new Answer();
            answer.setQuestion(q);
            answer.setResponseText(input.responseText());
            answer.setSelectedValue(input.selectedValue());
            answers.add(answer);
        }

        // Evaluate through the same engine as real evaluation — the returned
        // maturityLabel is DERIVED from the score via the pillar's thresholds,
        // matching what the real assessment flow would persist.
        Instant start = Instant.now();
        EvaluationEngine.PillarResult result = evaluationEngine.evaluatePillar(pillar, answers);
        long latencyMs = Duration.between(start, Instant.now()).toMillis();

        TryItOutResponse response = new TryItOutResponse(
                result.aiResult(),
                result.scorePercentage(),
                result.maturityLabel(),
                "default",
                latencyMs,
                result.rawResponse()
        );

        return ResponseEntity.ok(response);
    }
}
