package com.bvisionry.testimonial.entity;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A customer testimonial shown in the public homepage marquee. Stored as a row
 * (rather than hardcoded in the frontend) so super admins can add, edit, hide,
 * reorder and attach a photo to each one without a code change.
 */
@Entity
@Table(name = "testimonials")
@Getter
@Setter
@NoArgsConstructor
public class Testimonial extends BaseEntity {

    /** Person's name (e.g. "Ahmed Swailem"). */
    @Column(nullable = false, length = 160)
    private String name;

    /** Role / organisation line shown under the name. Optional. */
    @Column(length = 200)
    private String title;

    /** The testimonial body. */
    @Column(nullable = false, columnDefinition = "text")
    private String quote;

    /** Year the testimonial relates to (e.g. 2026). Optional. */
    @Column
    private Integer year;

    /** Star rating, 1–5. */
    @Column(nullable = false)
    private int rating;

    /**
     * Stored photo reference: a {@code minio://bucket/key} marker (uploaded via
     * the media endpoint) or a plain external URL. Resolved to a browsable URL
     * on read via {@code MediaService.resolveUrl}. Optional.
     */
    @Column(name = "photo_url", length = 512)
    private String photoUrl;

    /** When false, the testimonial is hidden from the public site but kept. */
    @Column(nullable = false)
    private boolean published = true;

    /** Ascending display order in the marquee (then by createdAt). */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
