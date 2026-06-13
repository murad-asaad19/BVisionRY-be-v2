package com.bvisionry.aicalllog.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.AICallStatus;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

@Entity
@Table(name = "ai_call_logs")
@Getter
@Setter
@NoArgsConstructor
public class AICallLog extends BaseEntity {

    @Column(name = "call_type", nullable = false, length = 50)
    private String callType;

    @Column(name = "pillar_name", length = 255)
    private String pillarName;

    @Column(name = "submission_id")
    private UUID submissionId;

    @Column(name = "pipeline_id")
    private UUID pipelineId;

    @Column(nullable = false)
    private String model;

    @Column(name = "called_at", nullable = false)
    private Instant calledAt;

    @Column(name = "elapsed_ms", nullable = false)
    private Integer elapsedMs;

    @Column(name = "system_prompt", columnDefinition = "TEXT")
    private String systemPrompt;

    @Column(name = "user_message", columnDefinition = "TEXT")
    private String userMessage;

    @Column(name = "raw_response", columnDefinition = "TEXT")
    private String rawResponse;

    @Column(name = "error_message", columnDefinition = "TEXT")
    private String errorMessage;

    @Column(name = "input_tokens")
    private Integer inputTokens;

    @Column(name = "output_tokens")
    private Integer outputTokens;

    @Column(name = "cache_creation_tokens")
    private Integer cacheCreationTokens;

    @Column(name = "cache_read_tokens")
    private Integer cacheReadTokens;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private AICallStatus status;
}
