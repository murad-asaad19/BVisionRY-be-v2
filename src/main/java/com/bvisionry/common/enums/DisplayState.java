package com.bvisionry.common.enums;

/**
 * UI-facing display state for an organization, derived from persisted state +
 * activity. Computed server-side so the frontend doesn't redo the logic in
 * three places.
 *
 * Order of evaluation (first match wins):
 *   1. !isActive → SUSPENDED
 *   2. isOnTrial() → TRIAL
 *   3. lastActiveAt is null OR < now - IDLE_DAYS → IDLE
 *   4. otherwise → ACTIVE
 */
public enum DisplayState {
    ACTIVE,
    TRIAL,
    IDLE,
    SUSPENDED
}
