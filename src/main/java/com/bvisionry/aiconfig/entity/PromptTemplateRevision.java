package com.bvisionry.aiconfig.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * An immutable point-in-time snapshot of a {@link PromptTemplate}'s content. Every edit
 * appends one; {@code PromptTemplate.currentRevisionId} points at the latest. Evaluations
 * persist the revision id in their provenance, so a stored evaluation always resolves to the
 * exact prompt text that produced it — even after later edits mutate the template row.
 */
@Entity
@Table(name = "prompt_template_revisions")
@Getter
@Setter
@NoArgsConstructor
public class PromptTemplateRevision {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(name = "template_id", nullable = false, updatable = false)
    private UUID templateId;

    @Column(nullable = false, columnDefinition = "TEXT")
    private String content;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        if (createdAt == null) {
            createdAt = Instant.now();
        }
    }
}
