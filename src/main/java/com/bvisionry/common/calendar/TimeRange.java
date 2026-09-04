package com.bvisionry.common.calendar;

import java.time.Instant;

/** A half-open busy interval {@code [start, end)} on someone's calendar. */
public record TimeRange(Instant start, Instant end) {
}
