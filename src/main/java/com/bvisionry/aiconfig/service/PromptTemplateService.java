package com.bvisionry.aiconfig.service;

import com.bvisionry.aiconfig.dto.PromptTemplateResponse;
import com.bvisionry.aiconfig.dto.PromptTemplateUpdateRequest;
import com.bvisionry.aiconfig.entity.PromptTemplate;
import com.bvisionry.aiconfig.repository.PromptTemplateRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.common.enums.PromptType;
import com.bvisionry.common.exception.ResourceNotFoundException;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.Arrays;
import java.util.List;
import java.util.Map;

@Service
@RequiredArgsConstructor
public class PromptTemplateService {

    private final PromptTemplateRepository promptRepository;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public PromptTemplateResponse getActivePrompt(PromptType promptType) {
        PromptTemplate template = promptRepository.findByPromptType(promptType)
                .orElseThrow(() -> new ResourceNotFoundException("PromptTemplate", promptType.name()));
        return toResponse(template);
    }

    @Transactional(readOnly = true)
    public List<PromptTemplateResponse> getAllActivePrompts() {
        return Arrays.stream(PromptType.values())
                .map(type -> promptRepository.findByPromptType(type))
                .filter(java.util.Optional::isPresent)
                .map(java.util.Optional::get)
                .map(this::toResponse)
                .toList();
    }

    @Transactional
    public PromptTemplateResponse updatePrompt(PromptType promptType, PromptTemplateUpdateRequest request) {
        PromptTemplate template = promptRepository.findByPromptType(promptType)
                .orElseThrow(() -> new ResourceNotFoundException("PromptTemplate", promptType.name()));

        template.setContent(request.content());

        PromptTemplate saved = promptRepository.save(template);

        auditService.log(null, null, "PROMPT_UPDATED", "PromptTemplate", saved.getId(),
                Map.of("promptType", promptType.name()));

        return toResponse(saved);
    }

    /**
     * Returns the active prompt content for a given type. Used by OpenRouterChatService.
     */
    @Transactional(readOnly = true)
    public String getActivePromptContent(PromptType promptType) {
        return getActivePrompt(promptType).content();
    }

    private PromptTemplateResponse toResponse(PromptTemplate template) {
        return new PromptTemplateResponse(
                template.getId(),
                template.getPromptType(),
                template.getContent(),
                template.getCreatedAt()
        );
    }

}
