package com.bvisionry.platform;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.Instant;
import java.util.UUID;

/**
 * Single-row-per-key value store for platform-wide tunable knobs.
 *
 * <p>A row carries exactly one of {@link #valueInt} (numeric knobs like
 * attention thresholds) or {@link #valueText} (free-form strings like
 * notification recipient lists). The "exactly one" invariant is enforced at
 * the service layer — keeping it out of a DB CHECK constraint means we don't
 * pay it on every read of a hot setting.
 */
@Entity
@Table(name = "platform_settings")
@Getter
@Setter
@NoArgsConstructor
public class PlatformSetting {

    @Id
    @Column(length = 100)
    private String key;

    @Column(name = "value_int")
    private Integer valueInt;

    @Column(name = "value_text", columnDefinition = "TEXT")
    private String valueText;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    @Column(name = "updated_by")
    private UUID updatedBy;
}
