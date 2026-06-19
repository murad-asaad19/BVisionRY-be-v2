package com.bvisionry.aiconfig.service;

import com.bvisionry.aiconfig.dto.PromptTemplateResponse;
import com.bvisionry.aiconfig.dto.PromptTemplateUpdateRequest;
import com.bvisionry.aiconfig.entity.PromptTemplate;
import com.bvisionry.aiconfig.repository.PromptTemplateRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.common.enums.PromptType;
import com.bvisionry.common.exception.ResourceNotFoundException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class PromptTemplateServiceTest {

    @Mock
    private PromptTemplateRepository promptRepository;

    @Mock
    private AuditService auditService;

    @InjectMocks
    private PromptTemplateService promptService;

    @Test
    void getActivePrompt_exists_returnsResponse() {
        PromptTemplate template = createTemplate(PromptType.TEAM_INSIGHT);
        when(promptRepository.findByPromptType(PromptType.TEAM_INSIGHT))
                .thenReturn(Optional.of(template));

        PromptTemplateResponse response = promptService.getActivePrompt(PromptType.TEAM_INSIGHT);

        assertThat(response.promptType()).isEqualTo(PromptType.TEAM_INSIGHT);
    }

    @Test
    void getActivePrompt_notFound_throwsException() {
        when(promptRepository.findByPromptType(PromptType.TEAM_INSIGHT))
                .thenReturn(Optional.empty());

        assertThatThrownBy(() -> promptService.getActivePrompt(PromptType.TEAM_INSIGHT))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void getAllActivePrompts_returnsList() {
        PromptTemplate t1 = createTemplate(PromptType.SYSTEM_PROMPT);
        PromptTemplate t2 = createTemplate(PromptType.TEAM_INSIGHT);

        // getAllActivePrompts() iterates every PromptType; only two are present
        // here, the rest resolve to Optional.empty(). lenient() so strict stubbing
        // tolerates the additional (unstubbed) findByPromptType calls.
        lenient().when(promptRepository.findByPromptType(PromptType.SYSTEM_PROMPT))
                .thenReturn(Optional.of(t1));
        lenient().when(promptRepository.findByPromptType(PromptType.TEAM_INSIGHT))
                .thenReturn(Optional.of(t2));

        List<PromptTemplateResponse> results = promptService.getAllActivePrompts();

        assertThat(results).hasSize(2);
    }

    @Test
    void updatePrompt_updatesInPlace() {
        PromptTemplate existing = createTemplate(PromptType.TEAM_INSIGHT);
        when(promptRepository.findByPromptType(PromptType.TEAM_INSIGHT))
                .thenReturn(Optional.of(existing));
        when(promptRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        PromptTemplateUpdateRequest request = new PromptTemplateUpdateRequest(
                "Updated prompt content for evaluation"
        );

        PromptTemplateResponse response = promptService.updatePrompt(PromptType.TEAM_INSIGHT, request);

        assertThat(response.content()).isEqualTo("Updated prompt content for evaluation");
        verify(promptRepository).save(existing);
    }

    private PromptTemplate createTemplate(PromptType type) {
        PromptTemplate template = new PromptTemplate();
        template.setId(UUID.randomUUID());
        template.setPromptType(type);
        template.setContent("Test content for " + type);
        template.setCreatedAt(Instant.now());
        return template;
    }
}
