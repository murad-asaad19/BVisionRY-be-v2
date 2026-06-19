package com.bvisionry.aiengine.eval;

import java.util.List;

/**
 * Result of benchmarking a model against the golden set: per-case outcomes plus
 * aggregates (in-band accuracy, schema-valid rate, average latency). This is the
 * evidence that makes "works regardless of model" a measured property — gate a
 * model switch on it rather than hoping.
 */
public record ModelEvalReport(
        String model,
        int total,
        int schemaValidCount,
        int inBandCount,
        double passRate,
        long avgLatencyMs,
        List<CaseResult> cases
) {

    public record CaseResult(
            String name,
            boolean schemaValid,
            Integer score,
            boolean inBand,
            long latencyMs,
            String note
    ) {}

    /** One-line human summary for logs / admin display. */
    public String summary() {
        return String.format(
                "Model %s — %d/%d in-band (%.0f%%), %d/%d schema-valid, avg %dms",
                model, inBandCount, total, passRate * 100, schemaValidCount, total, avgLatencyMs);
    }
}
