package com.bvisionry.calendar.provider;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

/** The registered {@link CalendarProvider}s, keyed by {@link CalendarProvider#id()}. */
@Component
public class CalendarProviders {

    private final Map<String, CalendarProvider> byId;

    public CalendarProviders(List<CalendarProvider> providers) {
        this.byId = providers.stream()
                .collect(Collectors.toUnmodifiableMap(CalendarProvider::id, Function.identity()));
    }

    /**
     * Empty when a connection row names a provider this build no longer has —
     * the callers all fail soft, so a removed provider degrades to "no calendar"
     * rather than to a startup crash or a 500 on every booking.
     */
    public Optional<CalendarProvider> byId(String id) {
        return Optional.ofNullable(id).map(byId::get);
    }
}
