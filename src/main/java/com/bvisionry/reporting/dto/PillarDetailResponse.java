package com.bvisionry.reporting.dto;

import com.bvisionry.reporting.service.NarrativeRedactor;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

public record PillarDetailResponse(
        UUID pillarId,
        String pillarName,
        String iconKey,
        BigDecimal scorePercentage,
        String maturityLabel,
        String whatThisScoreMeans,
        List<String> whatsWorking,
        List<String> whatCanImprove,
        String whyThisMattersForBusiness,
        Integer selfAssessmentGap,
        boolean aiFailed
) {

    /**
     * A copy with every free-text narrative field scrubbed of the member's
     * name(s). Structural fields (ids, score, labels, flags) are untouched.
     * Returns {@code this} when the redactor has nothing to scrub, so the
     * names-shown export path is a no-op. Centralising the redaction here means a
     * narrative field added to this record is scrubbed for every export without
     * each writer remembering to call {@code redact()}.
     */
    public PillarDetailResponse redacted(NarrativeRedactor redactor) {
        if (redactor == null || !redactor.isActive()) return this;
        return new PillarDetailResponse(
                pillarId(),
                pillarName(),
                iconKey(),
                scorePercentage(),
                maturityLabel(),
                redactor.redact(whatThisScoreMeans()),
                redactor.redact(whatsWorking()),
                redactor.redact(whatCanImprove()),
                redactor.redact(whyThisMattersForBusiness()),
                selfAssessmentGap(),
                aiFailed());
    }
}
