package com.bvisionry.comparison.domain;

/** How a pillar pair mapping row came to be. */
public enum MappingSource {
    /** Seeded by case-insensitive pillar-name match on first read of the pair. */
    AUTO,
    /** Set (or overridden) by a SUPER_ADMIN. */
    MANUAL
}
