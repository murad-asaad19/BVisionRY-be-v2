package com.bvisionry.businesscard.entity;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One link button on a {@link BusinessCard}, persisted inside the card's
 * {@code links} jsonb array (not its own table). {@code icon} is a string key
 * (WEBSITE | EMAIL | LINKEDIN | PHONE | LINK) the frontend maps to a glyph.
 *
 * A plain mutable POJO (no JPA annotations) so Hibernate's JSON type can
 * serialise/deserialise it with Jackson.
 */
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class CardLink {
    private String icon;
    private String label;
    private String url;
}
