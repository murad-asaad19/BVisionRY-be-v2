package com.bvisionry.workshops.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import jakarta.persistence.Column;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import lombok.Getter;
import lombok.Setter;

/**
 * Shared identity + audit-timestamp block for the workshops slice's entities:
 * a DB-generated ({@code gen_random_uuid()}) id and {@code created_at}/
 * {@code updated_at} maintained by the lifecycle callbacks.
 *
 * <p>Deliberately separate from {@link com.bvisionry.common.entity.BaseEntity}:
 * this slice uses {@link OffsetDateTime} timestamps and a database-generated id
 * ({@code insertable = false}), whereas {@code BaseEntity} uses {@code Instant}
 * and a Hibernate-assigned {@code GenerationType.UUID}. Folding these entities
 * into {@code BaseEntity} would silently change both the id-generation strategy
 * and the timestamp type.
 */
@MappedSuperclass
@Getter
@Setter
public abstract class WorkshopBaseEntity {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    @PrePersist
    void onCreate() {
        OffsetDateTime now = OffsetDateTime.now();
        if (createdAt == null) {
            createdAt = now;
        }
        updatedAt = now;
    }

    @PreUpdate
    void onUpdate() {
        updatedAt = OffsetDateTime.now();
    }
}
