package com.bvisionry.common.calendar;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Shared-kernel view of "when is this user busy on their REAL calendar"
 * (sessions spec v2 §7). {@code coaching} subtracts the answer from the slots
 * it offers; the {@code calendar} slice answers it from the user's connected
 * provider. Same seam as {@link com.bvisionry.common.media.MediaUrlPort}: the
 * ArchUnit ratchet forbids a new coaching → calendar import, so the contract
 * lives here and the implementation lives there.
 *
 * <p>Implementations MUST be quiet: no connection, an expired grant or a
 * provider outage all return an empty list (and log), because a slot picker
 * that fails closed would make every coach unbookable the moment Google hiccups.
 */
public interface CalendarBusyPort {

    List<TimeRange> busy(UUID userId, Instant from, Instant to);
}
