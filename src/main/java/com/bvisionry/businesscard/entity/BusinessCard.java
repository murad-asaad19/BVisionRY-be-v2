package com.bvisionry.businesscard.entity;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import java.util.ArrayList;
import java.util.List;

/**
 * A public, QR-shareable digital business card (e.g. {@code /card/razan-jalajel}).
 * Stored as a row (rather than hardcoded in the frontend) so super admins can add,
 * edit, hide, reorder and re-link cards without a code change. The link buttons
 * live in the {@code links} jsonb array; the portrait is an uploaded media marker
 * resolved on read via {@code MediaService.resolveUrl}.
 */
@Entity
@Table(name = "business_cards")
@Getter
@Setter
@NoArgsConstructor
public class BusinessCard extends BaseEntity {

    /** Public URL key — {@code /card/{slug}}. Lowercase, hyphenated, unique. */
    @Column(nullable = false, length = 80)
    private String slug;

    /** Person's display name (e.g. "Razan Jalajel"). */
    @Column(nullable = false, length = 160)
    private String name;

    /** Role line shown under the name (e.g. "Co-founder & CEO"). Optional. */
    @Column(length = 200)
    private String title;

    /** Short positioning line shown under the role. Optional. */
    @Column(columnDefinition = "text")
    private String tagline;

    /**
     * Optional substring of {@link #tagline} rendered bold on the card (e.g.
     * "build"). Mirrors the About page's goldFragment pattern — keeps the copy
     * single-sourced rather than splitting it into two columns.
     */
    @Column(name = "tagline_bold", length = 120)
    private String taglineBold;

    /**
     * Stored portrait reference: a {@code minio://bucket/key} marker (uploaded via
     * the media endpoint) or a plain external URL. Resolved to a browsable URL on
     * read via {@code MediaService.resolveUrl}. Optional.
     */
    @Column(name = "photo_url", length = 512)
    private String photoUrl;

    /** Link buttons, in display order. Persisted as a jsonb array. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private List<CardLink> links = new ArrayList<>();

    /** When false, the card is hidden (the public {@code /card/{slug}} 404s) but kept. */
    @Column(nullable = false)
    private boolean published = true;

    /** Ascending order in the admin list (then by createdAt). */
    @Column(name = "display_order", nullable = false)
    private int displayOrder;
}
