package com.bvisionry.aiconfig.entity;

import com.bvisionry.common.entity.BaseEntity;
import com.bvisionry.common.enums.AIProvider;
import jakarta.persistence.Column;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.math.BigDecimal;
import java.util.UUID;

@Entity
@Table(name = "ai_configurations")
@Getter
@Setter
@NoArgsConstructor
public class AIConfiguration extends BaseEntity {

    /**
     * The transport always routes through OpenRouter's OpenAI-compatible endpoint
     * (see {@code Lc4jChatModelProvider}), so OPENROUTER is the correct default: it
     * keeps a fresh install's key slot aligned with the wire. The column is retained
     * only because {@code AIModelCatalogService} still branches on it for the upstream
     * /models listing.
     */
    @Enumerated(EnumType.STRING)
    @Column(nullable = false)
    private AIProvider provider = AIProvider.OPENROUTER;

    @Column(name = "openrouter_api_key_encrypted", columnDefinition = "TEXT")
    private String openRouterApiKeyEncrypted;

    @Column(name = "anthropic_api_key_encrypted", columnDefinition = "TEXT")
    private String anthropicApiKeyEncrypted;

    @Column(name = "default_evaluation_model", nullable = false)
    private String defaultEvaluationModel = "anthropic/claude-sonnet-4";

    /** Model for public (QR-link) assessment evaluations; null = inherit {@link #defaultEvaluationModel}. */
    @Column(name = "public_assessment_model")
    private String publicAssessmentModel;

    @Column(name = "default_insight_model", nullable = false)
    private String defaultInsightModel = "anthropic/claude-sonnet-4";

    @Column(name = "evaluation_temperature", nullable = false)
    private BigDecimal evaluationTemperature = new BigDecimal("0.30");

    @Column(name = "insight_temperature", nullable = false)
    private BigDecimal insightTemperature = new BigDecimal("0.70");

    @Column(name = "max_tokens_evaluation", nullable = false)
    private int maxTokensEvaluation = 2048;

    @Column(name = "max_tokens_insight", nullable = false)
    private int maxTokensInsight = 4096;

    @Column(name = "updated_by")
    private UUID updatedBy;
}
