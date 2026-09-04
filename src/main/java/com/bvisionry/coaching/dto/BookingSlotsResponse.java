package com.bvisionry.coaching.dto;

import java.time.Instant;
import java.util.List;

/**
 * {@code GET /api/my/program/tasks/{taskId}/booking/slots} (spec §6.2). The
 * zone travels with the slots so the picker can show the member what the
 * coach's day looks like alongside their own — the instants themselves are
 * absolute and the browser renders them locally.
 */
public record BookingSlotsResponse(String timeZone, List<Instant> slots) {}
