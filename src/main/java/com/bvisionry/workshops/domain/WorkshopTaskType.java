package com.bvisionry.workshops.domain;

public enum WorkshopTaskType {
    /** Swipe cards left/right against an admin answer key, with a retry gate. */
    SORT,
    /** Score the "left" pile of the nearest SORT above, 0–100 each. */
    WEIGHT,
    /** Ranked top-N of the nearest WEIGHT above; the lead's last one shares. */
    TOP,
    /** Pick one card from the nearest TOP above and answer a free-text prompt. */
    QUESTION
}
