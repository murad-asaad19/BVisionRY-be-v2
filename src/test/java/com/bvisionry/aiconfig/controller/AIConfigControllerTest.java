package com.bvisionry.aiconfig.controller;

import com.bvisionry.aiconfig.dto.AIConfigResponse;
import com.bvisionry.aiconfig.dto.AIConfigUpdateRequest;
import com.bvisionry.aiconfig.dto.ApiKeyUpdateRequest;
import com.bvisionry.aiconfig.dto.OpenRouterModel;
import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiconfig.service.AIModelCatalogService;
import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.auth.CookieService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.jwt.JwtProvider;
import com.bvisionry.common.enums.AIProvider;
import com.bvisionry.common.web.ClientIpResolver;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.http.converter.autoconfigure.HttpMessageConvertersAutoConfiguration;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@WebMvcTest(AIConfigController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import({JacksonAutoConfiguration.class, HttpMessageConvertersAutoConfiguration.class})
class AIConfigControllerTest {

    @Autowired
    private MockMvc mockMvc;

    private final ObjectMapper objectMapper = new ObjectMapper().registerModule(new JavaTimeModule());

    @MockitoBean
    private AIConfigService configService;

    @MockitoBean
    private AIModelCatalogService modelService;

    // Required by JwtAuthenticationFilter / SurveySubmitRateLimitFilter
    // (@Component on classpath; even with addFilters=false Spring still
    // instantiates these beans, so each constructor dep needs a stand-in).
    @MockitoBean
    private JwtProvider jwtProvider;

    @MockitoBean
    private CookieService cookieService;

    @MockitoBean
    private UserRepository userRepository;

    @MockitoBean
    private RateLimitService rateLimitService;

    @MockitoBean
    private ClientIpResolver clientIpResolver;

    @Test
    void getConfig_returnsConfig() throws Exception {
        AIConfigResponse response = new AIConfigResponse(
                UUID.randomUUID(), AIProvider.OPENROUTER,
                true, "sk-or***1234",
                false, null,
                "anthropic/claude-sonnet-4", null, "anthropic/claude-sonnet-4",
                new BigDecimal("0.30"), new BigDecimal("0.70"),
                2048, 4096, Instant.now()
        );

        when(configService.getConfig()).thenReturn(response);

        mockMvc.perform(get("/api/ai-config"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.provider").value("OPENROUTER"))
                .andExpect(jsonPath("$.openRouterKeyConfigured").value(true))
                .andExpect(jsonPath("$.openRouterKeyMasked").value("sk-or***1234"))
                .andExpect(jsonPath("$.anthropicKeyConfigured").value(false))
                .andExpect(jsonPath("$.defaultEvaluationModel").value("anthropic/claude-sonnet-4"));
    }

    @Test
    void updateConfig_validRequest_returnsUpdated() throws Exception {
        AIConfigUpdateRequest request = new AIConfigUpdateRequest(
                AIProvider.ANTHROPIC,
                "claude-sonnet-4-5", null, "claude-sonnet-4-5",
                new BigDecimal("0.50"), new BigDecimal("0.80"),
                3000, 5000
        );

        AIConfigResponse response = new AIConfigResponse(
                UUID.randomUUID(), AIProvider.ANTHROPIC,
                false, null,
                true, "sk-ant***1234",
                "claude-sonnet-4-5", null, "claude-sonnet-4-5",
                new BigDecimal("0.50"), new BigDecimal("0.80"),
                3000, 5000, Instant.now()
        );

        when(configService.updateConfig(any())).thenReturn(response);

        mockMvc.perform(put("/api/ai-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.defaultEvaluationModel").value("claude-sonnet-4-5"));
    }

    @Test
    void updateConfig_invalidRequest_returns400() throws Exception {
        AIConfigUpdateRequest request = new AIConfigUpdateRequest(
                AIProvider.OPENROUTER,
                "", null, "", new BigDecimal("-1"), new BigDecimal("3.0"), 10, 10
        );

        mockMvc.perform(put("/api/ai-config")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void updateApiKey_validKey_returns204() throws Exception {
        ApiKeyUpdateRequest request = new ApiKeyUpdateRequest(AIProvider.ANTHROPIC, "sk-ant-api03-newkey");

        mockMvc.perform(put("/api/ai-config/api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isNoContent());

        verify(configService).updateApiKey(any());
    }

    @Test
    void updateApiKey_blankKey_returns400() throws Exception {
        ApiKeyUpdateRequest request = new ApiKeyUpdateRequest(AIProvider.OPENROUTER, "");

        mockMvc.perform(put("/api/ai-config/api-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(request)))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getModels_returnsModelList() throws Exception {
        List<OpenRouterModel> models = List.of(
                new OpenRouterModel("anthropic/claude-sonnet-4", "Claude Sonnet 4",
                        "Advanced model", new OpenRouterModel.Pricing("0.003", "0.015"), 200000),
                new OpenRouterModel("openai/gpt-4o", "GPT-4o",
                        "Multimodal model", new OpenRouterModel.Pricing("0.005", "0.015"), 128000)
        );

        when(modelService.getAvailableModels()).thenReturn(models);

        mockMvc.perform(get("/api/ai-config/models"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value("anthropic/claude-sonnet-4"));
    }
}
