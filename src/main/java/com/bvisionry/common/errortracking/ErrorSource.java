package com.bvisionry.common.errortracking;

/** Which tier an aggregated error came from. Mirrors the DB CHECK on error_events.source. */
public enum ErrorSource {
    BACKEND,
    WEB
}
