package com.bvisionry.notification.entity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

@Entity
@Table(name = "email_templates")
@Getter
@Setter
@NoArgsConstructor
public class EmailTemplate {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "template_key", nullable = false, length = 64)
    private EmailTemplateKey key;

    // Legacy raw-HTML columns retained for one release in case of rollback;
    // new field-driven saves leave these null.
    @Column(columnDefinition = "TEXT")
    private String subject;

    @Column(name = "html_body", columnDefinition = "TEXT")
    private String htmlBody;

    // Values are polymorphic: String for text fields (PLAIN_TEXT / RICH_TEXT / CTA_LABEL)
    // and List<String> for LIST fields. Hibernate + Jackson serialize both shapes
    // round-trip through jsonb without intermediate encoding.
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_values", columnDefinition = "jsonb")
    private Map<String, Object> fieldValues;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
