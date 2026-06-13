package com.bvisionry.lead;

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
 * Captures a Book-a-Demo lead submitted via the marketing modal.
 * Intentionally does NOT extend BaseEntity — leads are append-only,
 * have no updatedAt, and the id column uses the DB default gen_random_uuid()
 * rather than Hibernate's UUID generator for full Flyway alignment.
 */
@Entity
@Table(name = "leads")
@Getter
@Setter
@NoArgsConstructor
public class Lead {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private String name;

    @Column(nullable = false)
    private String email;

    @Column(nullable = false)
    private String organization;

    @Column(nullable = false)
    private String role;

    @Column(name = "program_type", nullable = false)
    private String programType;

    /** e.g. "1-50", "51-250" — nullable (optional in the form). */
    @Column(name = "cohort_size")
    private String cohortSize;

    @Column(columnDefinition = "TEXT", nullable = false)
    private String message;

    /** Origin of the lead, e.g. "book-demo-modal". Nullable. */
    @Column
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
