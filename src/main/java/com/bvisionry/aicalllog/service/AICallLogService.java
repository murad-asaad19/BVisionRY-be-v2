package com.bvisionry.aicalllog.service;

import com.bvisionry.aicalllog.dto.AICallLogEntry;
import com.bvisionry.aicalllog.dto.AICallLogResponse;
import com.bvisionry.aicalllog.entity.AICallLog;
import com.bvisionry.aicalllog.repository.AICallLogRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
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
            e.setSystemPrompt(entry.systemPrompt());
            e.setUserMessage(entry.userMessage());
            e.setRawResponse(entry.rawResponse());
            e.setErrorMessage(entry.errorMessage());
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
}
