package com.bvisionry.calendar;

import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.calendar.domain.CoachCalendarConnection;
import com.bvisionry.calendar.domain.CoachCalendarConnectionRepository;
import com.bvisionry.calendar.provider.CalendarProvider;
import com.bvisionry.common.crypto.SecretEncryptionService;

import lombok.RequiredArgsConstructor;

/**
 * Everything the calendar slice WRITES: the coach's connection row and the two
 * columns a synced session carries ({@code sessions.calendar_event_id},
 * {@code sessions.meeting_url}).
 *
 * <h2>Why the writes live behind a service the handler calls</h2>
 * {@link CalendarSyncHandler} runs {@code AFTER_COMMIT}, where there is no live
 * transaction, so a {@code @Transactional} annotation on the listener method
 * itself would attach to nothing. Each write below is therefore its own
 * {@code REQUIRES_NEW} unit on a bean the proxy actually wraps — and the slow
 * part (the HTTPS round trip to Google) deliberately happens OUTSIDE all of
 * them, so a provider timeout never holds a database connection open.
 *
 * <h2>Why raw SQL for the two session columns</h2>
 * {@code sessions} belongs to {@code engagement}; the architecture ratchet
 * forbids importing its entity, and these two columns are the calendar slice's
 * own data living on someone else's row. A two-column UPDATE by primary key is
 * the cross-slice convention here.
 */
@Service
@RequiredArgsConstructor
public class CalendarConnectionService {

    private final CoachCalendarConnectionRepository repository;
    private final NamedParameterJdbcTemplate jdbc;
    private final SecretEncryptionService secrets;

    @Transactional(readOnly = true)
    public Optional<CoachCalendarConnection> find(UUID userId) {
        return repository.findById(userId);
    }

    /**
     * Store (or replace) the coach's grant. Re-connecting overwrites the refresh
     * token and CLEARS {@code lastError} — reconnecting is precisely the fix for
     * the error the coach was shown, so leaving it would keep accusing them.
     */
    @Transactional
    public void connect(UUID userId, String provider, CalendarProvider.Grant grant) {
        CoachCalendarConnection connection = repository.findById(userId)
                .orElseGet(CoachCalendarConnection::new);
        connection.setUserId(userId);
        connection.setProvider(provider);
        connection.setAccountEmail(grant.accountEmail());
        connection.setRefreshTokenEnc(secrets.encrypt(grant.refreshToken()));
        if (connection.getCalendarId() == null) {
            connection.setCalendarId(CoachCalendarConnection.DEFAULT_CALENDAR_ID);
        }
        connection.setConnectedAt(OffsetDateTime.now());
        connection.setLastError(null);
        repository.save(connection);
    }

    @Transactional
    public void disconnect(UUID userId) {
        repository.deleteById(userId);
    }

    /** The event id we wrote when this session was scheduled, or empty. */
    @Transactional(readOnly = true)
    public Optional<String> calendarEventId(UUID sessionId) {
        return jdbc.queryForList("SELECT calendar_event_id FROM sessions WHERE id = :id",
                        Map.of("id", sessionId), String.class)
                .stream()
                .filter(id -> id != null && !id.isBlank())
                .findFirst();
    }

    /** The session now has a real calendar event: pin it to the row and clear the error. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEventCreated(UUID sessionId, UUID userId,
                                   CalendarProvider.CreatedEvent event) {
        jdbc.update("""
                UPDATE sessions
                   SET calendar_event_id = :eventId,
                       meeting_url       = :meetingUrl
                 WHERE id = :id
                """, new MapSqlParameterSource()
                .addValue("eventId", event.externalId())
                .addValue("meetingUrl", event.meetingUrl())
                .addValue("id", sessionId));
        recordOutcome(userId, null);
    }

    /**
     * The session is no longer scheduled. Both columns are cleared whether or not
     * the provider delete succeeded: the row is not scheduled any more, so a
     * lingering event id would only invite a second delete of an event nobody can
     * reach — the failure is recorded in {@code lastError} instead.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordEventCleared(UUID sessionId, UUID userId, String error) {
        jdbc.update("""
                UPDATE sessions
                   SET calendar_event_id = NULL,
                       meeting_url       = NULL
                 WHERE id = :id
                """, Map.of("id", sessionId));
        recordOutcome(userId, error);
    }

    /** Record a failure against the connection without touching any session row. */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void recordError(UUID userId, String error) {
        recordOutcome(userId, error);
    }

    /**
     * Written with SQL rather than through the entity so it cannot collide with a
     * concurrent {@link #connect} loading the same row: this is a status stamp,
     * never a change to the grant.
     *
     * <p>Two statements rather than one with a {@code CASE}: the branch is
     * already decided in Java, and a single-statement version would bind the same
     * NULL parameter twice, which Postgres cannot type-infer.
     */
    private void recordOutcome(UUID userId, String error) {
        if (error == null) {
            jdbc.update("""
                    UPDATE coach_calendar_connections
                       SET last_synced_at = now(), last_error = NULL
                     WHERE user_id = :userId
                    """, Map.of("userId", userId));
            return;
        }
        jdbc.update("""
                UPDATE coach_calendar_connections
                   SET last_error = :error
                 WHERE user_id = :userId
                """, Map.of("error", truncate(error), "userId", userId));
    }

    /** {@code last_error} is shown verbatim to a coach; a stack-trace-long message is noise. */
    private static String truncate(String message) {
        return message.length() <= 500 ? message : message.substring(0, 500);
    }
}
