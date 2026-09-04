package com.bvisionry.common.enums;

/**
 * The three session types (spec §4), each keyed to the participation-config
 * category it feeds. A type whose category was removed from the config simply
 * contributes nothing to the score — it never crashes the computation.
 */
public enum SessionType {
    WORKSHOP("workshops"),
    COACHING_1ON1("coaching_1on1"),
    COACHING_GROUP("coaching_group");

    private final String categoryKey;

    SessionType(String categoryKey) {
        this.categoryKey = categoryKey;
    }

    /** The participation-formula category key this session type feeds. */
    public String categoryKey() {
        return categoryKey;
    }
}
