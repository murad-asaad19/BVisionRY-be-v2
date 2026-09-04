package com.bvisionry.calendar;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.bvisionry.calendar.domain.CoachCalendarConnection;
import com.bvisionry.calendar.provider.CalendarProvider;
import com.bvisionry.calendar.provider.CalendarProviders;
import com.bvisionry.common.calendar.CalendarBusyPort;
import com.bvisionry.common.calendar.TimeRange;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Answers {@link CalendarBusyPort} from the coach's connected calendar, so the
 * slot picker never offers a time the coach is already booked for elsewhere.
 *
 * <p><strong>It fails open, on purpose.</strong> No connection, a dead grant or
 * a Google outage all return an empty list. Failing closed would be worse than
 * the bug it prevents: an empty busy list costs at most a double-booking the
 * coach can cancel, while an exception here would make every coach unbookable
 * platform-wide the moment Google hiccups. The reason still lands in
 * {@code last_error}, where {@code /app/team} shows it.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class CalendarBusyAdapter implements CalendarBusyPort {

    private final CalendarProviders providers;
    private final CalendarConnectionService connections;

    @Override
    public List<TimeRange> busy(UUID userId, Instant from, Instant to) {
        CoachCalendarConnection connection = connections.find(userId).orElse(null);
        if (connection == null) {
            return List.of();
        }
        CalendarProvider provider = providers.byId(connection.getProvider()).orElse(null);
        if (provider == null) {
            return List.of();
        }
        try {
            return provider.busy(connection, from, to);
        } catch (RuntimeException e) {
            log.warn("Free/busy lookup failed for user {}: {}", userId, e.getMessage());
            connections.recordError(userId, e.getMessage());
            return List.of();
        }
    }
}
