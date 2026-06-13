package com.bvisionry.aiconfig.controller;

import com.bvisionry.aiconfig.dto.AIConfigResponse;
import com.bvisionry.aiconfig.dto.AIConfigUpdateRequest;
import com.bvisionry.aiconfig.dto.ApiKeyUpdateRequest;
import com.bvisionry.aiconfig.dto.OpenRouterModel;
import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiconfig.service.AIModelCatalogService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/ai-config")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class AIConfigController {

    private final AIConfigService configService;
    private final AIModelCatalogService modelService;

    @GetMapping
    public ResponseEntity<AIConfigResponse> getConfig() {
        return ResponseEntity.ok(configService.getConfig());
    }

    @PutMapping
    public ResponseEntity<AIConfigResponse> updateConfig(@Valid @RequestBody AIConfigUpdateRequest request) {
        return ResponseEntity.ok(configService.updateConfig(request));
    }

    @PutMapping("/api-key")
    public ResponseEntity<Void> updateApiKey(@Valid @RequestBody ApiKeyUpdateRequest request) {
        configService.updateApiKey(request);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/models")
    public ResponseEntity<List<OpenRouterModel>> getModels() {
        return ResponseEntity.ok(modelService.getAvailableModels());
    }
}
