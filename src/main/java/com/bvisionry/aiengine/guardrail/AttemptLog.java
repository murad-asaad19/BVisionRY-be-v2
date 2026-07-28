package com.bvisionry.aiengine.guardrail;

import com.fasterxml.jackson.core.io.JsonStringEncoder;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Collects the per-attempt history of a single AI call so the repair round-trips
 * — invisible inside LangChain4j's retry loop — end up in the {@code ai_call_logs}
 * audit trail.
 *
 * <p>One instance per AI call, filled by {@link StructuredOutputGuardrail} as it
 * validates each response: every draft the model produced, and (when rejected) the
 * corrective message that was sent back. Without this, a repaired or exhausted call
 * logged only its FINAL state, so "why did the model end up here" was unanswerable
 * from the logs — the exact gap that made the FOCUS/FLOW incident take a live
 * provider-side transcript to diagnose.
 *
 * <p>Deliberately dependency-free on the DB/DTO layers: it produces a JSON array
 * string the caller stores verbatim.
 */
public final class AttemptLog {

    /** Outcome of one validated model response. */
    public enum Outcome { ACCEPTED, REPROMPTED }

    private record Attempt(int number, String response, Outcome outcome, String reason, String correction) {}

    // Escalation samples run concurrently against their own guardrails, but a single
    // guardrail's validate() can still be re-entered by the retry loop — keep it safe.
    private final List<Attempt> attempts = new CopyOnWriteArrayList<>();

    void record(String response, Outcome outcome, String reason, String correction) {
        attempts.add(new Attempt(attempts.size() + 1, response, outcome, reason, correction));
    }

    /** Number of model round-trips this call made (1 = clean first pass, no repair). */
    public int count() {
        return attempts.size();
    }

    /** True when at least one repair round-trip happened — i.e. the history adds information. */
    public boolean hasRepairs() {
        return attempts.size() > 1;
    }

    /**
     * The attempt history as a JSON array, ordered oldest-first. Returns {@code null}
     * when nothing was recorded so callers can skip the column entirely.
     */
    public String toJson() {
        if (attempts.isEmpty()) {
            return null;
        }
        StringBuilder sb = new StringBuilder("[");
        for (Attempt a : attempts) {
            if (sb.length() > 1) sb.append(',');
            sb.append("{\"attempt\":").append(a.number())
              .append(",\"outcome\":\"").append(a.outcome()).append('"')
              .append(",\"response\":").append(quote(a.response()));
            if (a.reason() != null) {
                sb.append(",\"reason\":").append(quote(a.reason()));
            }
            if (a.correction() != null) {
                sb.append(",\"correction\":").append(quote(a.correction()));
            }
            sb.append('}');
        }
        return sb.append(']').toString();
    }

    /** JSON string literal (or bare null) — Jackson's encoder handles all escaping. */
    private static String quote(String value) {
        if (value == null) {
            return "null";
        }
        return "\"" + new String(JsonStringEncoder.getInstance().quoteAsString(value)) + "\"";
    }
}
