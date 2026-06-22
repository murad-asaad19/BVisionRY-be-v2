package com.bvisionry.leadmagnet;

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
 * Captures a visitor's email submitted via the lead-magnet modal ("the science
 * behind the 11 pillars"). Append-only — these are marketing leads kept for
 * follow-up and export. Mirrors {@code com.bvisionry.lead.Lead}.
 */
@Entity
@Table(name = "lead_magnet_requests")
@Getter
@Setter
@NoArgsConstructor
public class LeadMagnetRequest {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    @Column(updatable = false, nullable = false)
    private UUID id;

    @Column(nullable = false)
    private String email;

    /** Origin of the request, e.g. "platform-pillars". Nullable. */
    @Column
    private String source;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @PrePersist
    protected void onCreate() {
        createdAt = Instant.now();
    }
}
