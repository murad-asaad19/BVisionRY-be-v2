package com.bvisionry.common.pdf.template;

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

/**
 * An admin's customization of one PDF template. A row exists only when at
 * least one field differs from its shipped default; {@code field_values}
 * holds exactly those differing fields.
 */
@Entity
@Table(name = "pdf_templates")
@Getter
@Setter
@NoArgsConstructor
public class PdfTemplate {

    @Id
    @Enumerated(EnumType.STRING)
    @Column(name = "template_key", nullable = false, length = 64)
    private PdfTemplateKey key;

    // Every PDF field is text; Object keeps the read path tolerant of a
    // hand-edited row (values are normalized to String on the way out).
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "field_values", columnDefinition = "jsonb")
    private Map<String, Object> fieldValues;

    @Column(name = "updated_by")
    private UUID updatedBy;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();
}
