package com.bvisionry.common.ai;

import com.bvisionry.common.enums.PromptType;

/**
 * Feature-neutral streaming chat seam. Feature slices that must not depend on
 * the {@code aiconfig}/{@code aiengine} features (ArchUnit ratchet) stream AI
 * completions through this port; the adapter in {@code config} resolves the
 * admin-managed prompt template, model, temperature and API key.
 */
public interface StreamingChatPort {

    /**
     * Streams one chat completion. The call is asynchronous: it returns
     * immediately and the handler is invoked from the transport's thread.
     *
     * @param systemPromptType the admin-managed template used as system prompt
     * @param userMessage      the fully assembled user message
     * @param maxTokens        output budget for this call
     * @param handler          receives tokens, then exactly one of complete/error
     */
    void stream(PromptType systemPromptType, String userMessage, int maxTokens, StreamHandler handler);

    interface StreamHandler {

        void onToken(String token);

        void onComplete(String fullText);

        void onError(Throwable error);
    }
}
