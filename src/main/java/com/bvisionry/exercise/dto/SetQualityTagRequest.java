package com.bvisionry.exercise.dto;

/**
 * Body for both quality-tag writes (spec §4): the mark-reviewed ride-along and
 * the standalone re-tag. A null {@code qualityTagKey} means "leave it alone" on
 * mark-reviewed and "clear it" on the standalone PATCH — the two verbs already
 * say which, so the field needs no third state.
 *
 * <p>Validated against the live §7 tag set server-side, never by an enum: the
 * tag set is super-admin configurable, so a compiled-in list would go stale the
 * first time somebody renamed a tag.
 */
public record SetQualityTagRequest(String qualityTagKey) {}
