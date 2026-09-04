package com.bvisionry.coaching.dto;

import jakarta.validation.constraints.NotNull;

/**
 * Body of {@code PUT /api/v1/coach/sessions/{id}/attendance/{memberId}}
 * (spec v2 §6.1): correct ONE mark on an already-held session. Boxed and
 * {@code @NotNull} so a missing field is a 400 rather than a silent "absent".
 */
public record AttendanceMarkRequest(@NotNull Boolean present) {}
