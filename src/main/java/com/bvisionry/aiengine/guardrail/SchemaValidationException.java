package com.bvisionry.aiengine.guardrail;

/**
 * Thrown in place of LangChain4j's {@code OutputGuardrailException} so the last raw
 * model output travels with the failure for audit persistence. When the repair retry
 * budget is exhausted the offending output is otherwise lost; carrying it here lets the
 * caller persist real evidence instead of a static generic message.
 */
public class SchemaValidationException extends RuntimeException {

    private final String rawModelOutput;

    public SchemaValidationException(String message, String rawModelOutput, Throwable cause) {
        super(message, cause);
        this.rawModelOutput = rawModelOutput;
    }

    public String getRawModelOutput() {
        return rawModelOutput;
    }
}
