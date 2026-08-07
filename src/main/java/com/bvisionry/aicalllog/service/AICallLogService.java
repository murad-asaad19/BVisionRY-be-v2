package com.bvisionry.aicalllog.service;

import com.bvisionry.aicalllog.dto.AICallLogEntry;
import com.bvisionry.aicalllog.dto.AICallLogResponse;
import com.bvisionry.aicalllog.entity.AICallLog;
import com.bvisionry.aicalllog.repository.AICallLogRepository;
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
     * The audit table is a growth risk (F41): ~6 AI calls per submission, each
     * persisting system_prompt + user_message + raw_response as TEXT on the same
     * Postgres serving the hot path. We accept that cost — a call log without the
     * prompt and response can't answer the question it exists to answer. Growth is
     * bounded by the per-field truncation cap plus {@link AICallLogRetentionJob}.
     */
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
            e.setRequestId(entry.requestId());
            e.setModel(entry.model());
            e.setCalledAt(entry.calledAt());
            e.setElapsedMs(entry.elapsedMs());
            e.setSystemPrompt(truncate(entry.systemPrompt()));
            e.setUserMessage(truncate(entry.userMessage()));
            e.setRawResponse(truncate(entry.rawResponse()));
            e.setErrorMessage(truncate(entry.errorMessage()));
            e.setInputTokens(entry.inputTokens());
            e.setOutputTokens(entry.outputTokens());
            e.setCacheCreationTokens(entry.cacheCreationTokens());
            e.setCacheReadTokens(entry.cacheReadTokens());
            e.setStatus(entry.status());
            e.setAttempts(entry.attempts() > 0 ? entry.attempts() : null);
            // Store the repair trail only when a repair actually happened: on a clean
            // single-attempt call the system_prompt/user_message/raw_response columns
            // already carry the whole story, so duplicating them here would double the
            // table's dominant growth term for no diagnostic gain.
            e.setAttemptHistory(entry.attempts() > 1 ? truncate(entry.attemptHistory()) : null);
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
