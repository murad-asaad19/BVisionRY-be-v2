package com.bvisionry.aicalllog.service;

import com.bvisionry.aicalllog.dto.AICallLogEntry;
import com.bvisionry.aicalllog.dto.AICallLogResponse;
import com.bvisionry.aicalllog.entity.AICallLog;
import com.bvisionry.aicalllog.repository.AICallLogRepository;
import com.bvisionry.common.enums.AICallStatus;
import com.bvisionry.common.util.TextTruncator;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.util.UUID;

@Service
@RequiredArgsConstructor
@Slf4j
public class AICallLogService {

    private final AICallLogRepository repository;

    /**
     * At launch scale the audit table is the dominant growth risk (F41): ~6 AI calls
     * per submission, each persisting full system_prompt + user_message + raw_response
     * as TEXT, on the same Postgres serving the hot path. By default we drop those
     * bulky payloads for SUCCESS rows (keeping model/timing/token metadata) and retain
     * them only for FAILED rows — which is exactly when the prompt/response is needed
     * to debug. Flip {@code store-success-payloads=true} to keep the full reproducible
     * trail at the cost of storage. Whatever IS stored is truncated to a hard cap.
     */
    @Value("${bvisionry.ai-call-log.store-success-payloads:false}")
    private boolean storeSuccessPayloads;
    @Value("${bvisionry.ai-call-log.max-payload-chars:10000}")
    private int maxPayloadChars;

    /**
     * Fire-and-forget write on the dedicated {@code aiLogExecutor}. Runs in a
     * fresh transaction so a caller-side rollback doesn't discard the log —
     * audit trails are most useful precisely when something went wrong.
     */
    @Async("aiLogExecutor")
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void record(AICallLogEntry entry) {
        try {
            AICallLog e = new AICallLog();
            e.setCallType(entry.callType());
            e.setPillarName(entry.pillarName());
            e.setSubmissionId(entry.submissionId());
            e.setPipelineId(entry.pipelineId());
            e.setModel(entry.model());
            e.setCalledAt(entry.calledAt());
            e.setElapsedMs(entry.elapsedMs());
            // Bound storage growth: keep heavy payloads only for failures (or when
            // explicitly enabled), always truncated to the configured cap.
            boolean keepPayload = storeSuccessPayloads || entry.status() != AICallStatus.SUCCESS;
            e.setSystemPrompt(keepPayload ? truncate(entry.systemPrompt()) : null);
            e.setUserMessage(keepPayload ? truncate(entry.userMessage()) : null);
            e.setRawResponse(keepPayload ? truncate(entry.rawResponse()) : null);
            e.setErrorMessage(truncate(entry.errorMessage()));
            e.setInputTokens(entry.inputTokens());
            e.setOutputTokens(entry.outputTokens());
            e.setCacheCreationTokens(entry.cacheCreationTokens());
            e.setCacheReadTokens(entry.cacheReadTokens());
            e.setStatus(entry.status());
            repository.save(e);
        } catch (Exception ex) {
            // Never let a logging failure surface — but make it loud in the logs
            // so we notice if the audit trail stops filling.
            log.error("Failed to persist AI call log (callType={}, submissionId={}): {}",
                    entry.callType(), entry.submissionId(), ex.getMessage(), ex);
        }
    }

    @Transactional(readOnly = true)
    public Page<AICallLogResponse> find(UUID pipelineId, UUID submissionId, int page, int size) {
        PageRequest pageable = PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "calledAt"));
        return repository.findFiltered(pipelineId, submissionId, pageable)
                .map(AICallLogResponse::from);
    }

    /**
     * Cap a stored payload field so a single pathological prompt/response can't bloat a
     * row. Truncates by code point (never splitting a surrogate pair) and strips NUL,
     * both of which Postgres would otherwise reject — failing the audit write entirely.
     */
    private String truncate(String value) {
        return TextTruncator.truncate(value, maxPayloadChars, "…[truncated]");
    }
}
