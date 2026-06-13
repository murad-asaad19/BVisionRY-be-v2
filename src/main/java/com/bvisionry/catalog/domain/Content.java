package com.bvisionry.catalog.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import org.hibernate.annotations.ColumnDefault;
import org.hibernate.annotations.Generated;

import com.fasterxml.jackson.annotation.JsonIgnore;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.PrePersist;
import jakarta.persistence.PreUpdate;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * An ordered learning item within a {@link Section} (video, article, quiz …).
 */
@Entity
@Table(name = "content")
@Getter
@Setter
public class Content {

    @Id
    @Generated
    @ColumnDefault("gen_random_uuid()")
    @Column(name = "id", nullable = false, updatable = false, insertable = false)
    private UUID id;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    // LAZY back-reference: never serialised. updateContent/createContent return
    // this entity after the tx/session closes (open-in-view=false); serialising
    // the uninitialised proxy would throw LazyInitializationException (HTTP 500).
    // The frontend never reads it.
    @JsonIgnore
    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "section_id", nullable = false)
    private Section section;

    @Column(name = "title", nullable = false, length = 200)
    private String title;

    @Enumerated(EnumType.STRING)
    @Column(name = "content_type", nullable = false, length = 20)
    private ContentType contentType = ContentType.VIDEO;

    @Column(name = "duration_min")
    private Integer durationMin;

    @Column(name = "allow_preview", nullable = false)
    private boolean allowPreview = false;

    @Column(name = "sequence", nullable = false)
    private int sequence = 0;

    /** Tiptap JSON document body for PAGE / slide content types. Nullable. */
    @Column(name = "body", columnDefinition = "text")
    private String body;

    /** HLS or direct video URL for VIDEO content. Nullable. */
    @Column(name = "video_url", length = 500)
    private String videoUrl;

    /** PDF or other asset URL for PDF / DOCUMENT content. Nullable. */
    @Column(name = "asset_url", length = 500)
    private String assetUrl;

    /**
     * For ASSIGNMENT-type lessons: the FRI pipeline this lesson embeds. Opening
     * the lesson resolves (or lazily creates) the member's assignment/submission
     * for this pipeline. Soft reference (no @ManyToOne), nullable for all other
     * content types. (V80)
     */
    @Column(name = "pipeline_id")
    private UUID pipelineId;

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
