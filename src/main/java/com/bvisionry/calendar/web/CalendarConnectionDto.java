package com.bvisionry.calendar.web;

import java.time.OffsetDateTime;

import com.bvisionry.calendar.domain.CoachCalendarConnection;

/**
 * The Calendar card on {@code /app/team} (sessions spec v2 §7/§11).
 *
 * <p>{@code lastError} is part of the contract, not a debugging leftover: every
 * calendar operation fails soft, so a connection that has quietly stopped
 * working looks identical to a healthy one without it.
 */
public record CalendarConnectionDto(boolean connected,
                                    String provider,
                                    String accountEmail,
                                    OffsetDateTime connectedAt,
                                    String lastError) {

    static final CalendarConnectionDto NOT_CONNECTED =
            new CalendarConnectionDto(false, null, null, null, null);

    static CalendarConnectionDto of(CoachCalendarConnection connection) {
        return new CalendarConnectionDto(true, connection.getProvider(),
                connection.getAccountEmail(), connection.getConnectedAt(),
                connection.getLastError());
    }
}
