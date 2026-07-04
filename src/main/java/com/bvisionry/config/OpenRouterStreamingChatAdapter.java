package com.bvisionry.config;

import java.util.List;

import org.springframework.stereotype.Component;

import com.bvisionry.aiconfig.entity.AIConfiguration;
import com.bvisionry.aiconfig.service.AIConfigService;
import com.bvisionry.aiconfig.service.PromptTemplateService;
import com.bvisionry.aiengine.transport.Lc4jChatModelProvider;
import com.bvisionry.common.ai.StreamingChatPort;
import com.bvisionry.common.enums.PromptType;

import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import dev.langchain4j.model.chat.response.ChatResponse;
import dev.langchain4j.model.chat.response.StreamingChatResponseHandler;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * {@link StreamingChatPort} adapter over the OpenRouter transport. Lives in
 * {@code config} (shared wiring layer) so feature slices — currently
 * {@code programflow} — can stream completions without depending on the
 * {@code aiconfig}/{@code aiengine} features. Uses the admin-managed insight
 * model and temperature.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class OpenRouterStreamingChatAdapter implements StreamingChatPort {

    private final Lc4jChatModelProvider modelProvider;
    private final AIConfigService configService;
    private final PromptTemplateService promptTemplateService;

    @Override
    public void stream(PromptType systemPromptType, String userMessage, int maxTokens, StreamHandler handler) {
        AIConfiguration config = configService.getConfigEntity();
        String systemPrompt = promptTemplateService.getActivePromptContent(systemPromptType);
        List<ChatMessage> messages = List.of(
                SystemMessage.from(systemPrompt),
                UserMessage.from(userMessage));

        modelProvider
                .streamingModelFor(config.getDefaultInsightModel(),
                        config.getInsightTemperature().doubleValue(), maxTokens)
                .chat(messages, new StreamingChatResponseHandler() {

                    @Override
                    public void onPartialResponse(String token) {
                        handler.onToken(token);
                    }

                    @Override
                    public void onCompleteResponse(ChatResponse response) {
                        handler.onComplete(response.aiMessage().text());
                    }

                    @Override
                    public void onError(Throwable error) {
                        log.warn("Streaming AI call ({}) failed: {}", systemPromptType, error.toString());
                        handler.onError(error);
                    }
                });
    }
}
