package com.bvisionry.calendar.provider;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.RestClient;

import com.bvisionry.calendar.domain.CoachCalendarConnection;
import com.bvisionry.common.calendar.TimeRange;
import com.bvisionry.common.crypto.SecretEncryptionService;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import lombok.extern.slf4j.Slf4j;

/**
 * Google Calendar over its REST API with a plain Spring {@link RestClient} —
 * deliberately no {@code google-api-client} dependency: this uses five endpoints
 * (token, events.insert, events.patch, events.delete, freeBusy.query) and the SDK would drag
 * in its own HTTP stack, its own JSON layer and its own credential store for
 * them.
 *
 * <p>It reuses the {@code bvisionry.oauth2.google.*} client the SSO login
 * already owns, so an operator configures ONE OAuth client, not two — the
 * calendar callback is simply a second authorised redirect URI on it.
 *
 * <h2>Scopes</h2>
 * {@code calendar.events} (create and delete the session's event) and
 * {@code calendar.freebusy} (busy intervals only — never event titles), plus
 * {@code openid email} because the coach must be shown WHICH Google account
 * they connected. {@code calendar.freebusy} is the narrowest scope that answers
 * "when is this coach busy"; {@code calendar.readonly} would answer it too and
 * hand us every event body we have no business reading.
 *
 * <h2>Access tokens</h2>
 * Only the refresh token is stored, encrypted. Access tokens are minted on
 * demand and cached in memory until a minute before they expire. The cache is
 * per-process and lossy by design: losing it costs one extra token call.
 */
@Component
@Slf4j
public class GoogleCalendarProvider implements CalendarProvider {

    public static final String ID = "GOOGLE";

    private static final String AUTH_ENDPOINT = "https://accounts.google.com/o/oauth2/v2/auth";
    private static final String TOKEN_ENDPOINT = "https://oauth2.googleapis.com/token";
    private static final String REVOKE_ENDPOINT = "https://oauth2.googleapis.com/revoke";
    private static final String CALENDAR_API = "https://www.googleapis.com/calendar/v3";

    private static final String SCOPES = String.join(" ",
            "openid",
            "email",
            "https://www.googleapis.com/auth/calendar.events",
            "https://www.googleapis.com/auth/calendar.freebusy");

    /** Refresh a minute early so a token cannot expire mid-request. */
    private static final Duration EXPIRY_MARGIN = Duration.ofSeconds(60);

    private static final ObjectMapper JSON = new ObjectMapper();

    private final RestClient http;
    private final SecretEncryptionService secrets;
    private final String clientId;
    private final String clientSecret;

    private final Map<UUID, CachedToken> accessTokens = new ConcurrentHashMap<>();

    public GoogleCalendarProvider(RestClient.Builder restClientBuilder,
                                  SecretEncryptionService secrets,
                                  @Value("${bvisionry.oauth2.google.client-id:}") String clientId,
                                  @Value("${bvisionry.oauth2.google.client-secret:}") String clientSecret) {
        this.http = restClientBuilder.build();
        this.secrets = secrets;
        this.clientId = clientId;
        this.clientSecret = clientSecret;
    }

    @Override
    public String id() {
        return ID;
    }

    /** True when this server has an OAuth client at all — the connect button is dead without one. */
    public boolean isConfigured() {
        return clientId != null && !clientId.isBlank();
    }

    @Override
    public String authorizationUrl(String state, String redirectUri) {
        return AUTH_ENDPOINT
                + "?client_id=" + enc(clientId)
                + "&redirect_uri=" + enc(redirectUri)
                + "&response_type=code"
                + "&scope=" + enc(SCOPES)
                // offline + consent together are what actually YIELD a refresh token:
                // offline asks for one, consent re-asks even when the coach already
                // approved these scopes — Google returns a refresh token only on a
                // fresh grant, so a reconnect without it silently gets none.
                + "&access_type=offline"
                + "&prompt=consent"
                + "&state=" + enc(state);
    }

    @Override
    public Grant exchange(String code, String redirectUri) {
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "authorization_code");
        form.add("code", code);
        form.add("redirect_uri", redirectUri);
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        JsonNode response = postForm(TOKEN_ENDPOINT, form);
        String refreshToken = text(response, "refresh_token");
        if (refreshToken == null) {
            throw new IllegalStateException("Google returned no refresh token. Remove Bvisionry at "
                    + "https://myaccount.google.com/permissions and connect again.");
        }
        String email = emailFromIdToken(text(response, "id_token"));
        if (email == null) {
            throw new IllegalStateException("Google returned no account email for the calendar grant.");
        }
        return new Grant(refreshToken, email);
    }

    @Override
    public CreatedEvent createEvent(CoachCalendarConnection connection, EventSpec spec) {
        Map<String, Object> body = new LinkedHashMap<>(fields(spec));
        body.put("conferenceData", Map.of("createRequest", Map.of(
                "requestId", spec.requestId(),
                "conferenceSolutionKey", Map.of("type", "hangoutsMeet"))));

        JsonNode event = http.post()
                // conferenceDataVersion=1 is what makes Google ACT on createRequest;
                // at version 0 the conference block is silently ignored and every
                // session would go out without a Meet link.
                .uri(CALENDAR_API + "/calendars/{calendarId}/events?conferenceDataVersion=1&sendUpdates=all",
                        calendarId(connection))
                .header("Authorization", "Bearer " + accessToken(connection))
                .contentType(MediaType.APPLICATION_JSON)
                .body(body)
                .retrieve()
                .body(JsonNode.class);

        return created(event);
    }

    /**
     * PATCH, not PUT: a partial update leaves every field we do not send alone —
     * which is how the existing {@code conferenceData} (the Meet room the guests
     * already have) survives the move. A full replace would have to re-send it,
     * and re-sending a createRequest mints a second room.
     *
     * <p>{@code conferenceDataVersion=1} is still needed for Google to ECHO the
     * conference back in the response, which is where {@code meetingUrl} is read
     * from; {@code sendUpdates=all} is what turns the change into the one
     * "updated" notice each guest gets.
     */
    @Override
    public CreatedEvent updateEvent(CoachCalendarConnection connection, String externalId,
                                    EventSpec spec) {
        JsonNode event = http.patch()
                .uri(CALENDAR_API + "/calendars/{calendarId}/events/{eventId}"
                                + "?conferenceDataVersion=1&sendUpdates=all",
                        calendarId(connection), externalId)
                .header("Authorization", "Bearer " + accessToken(connection))
                .contentType(MediaType.APPLICATION_JSON)
                .body(fields(spec))
                .retrieve()
                // Deleted (404) or cancelled (410) in the coach's own calendar:
                // there is nothing to move, so the caller creates a fresh event.
                .onStatus(status -> status.value() == 404 || status.value() == 410,
                        (request, response) -> {
                            throw new EventNotFound(
                                    "Google no longer has event " + externalId + ".");
                        })
                .body(JsonNode.class);

        return created(event);
    }

    /** The half of an event body that a create and a move both send. */
    private static Map<String, Object> fields(EventSpec spec) {
        List<Map<String, Object>> attendees = spec.attendeeEmails().stream()
                .filter(email -> email != null && !email.isBlank())
                .distinct()
                .<Map<String, Object>>map(email -> Map.of("email", email))
                .toList();
        return Map.of(
                "summary", spec.title(),
                "description", spec.description(),
                "start", Map.of("dateTime", spec.startsAt().toString()),
                "end", Map.of("dateTime", spec.endsAt().toString()),
                "attendees", attendees);
    }

    private static CreatedEvent created(JsonNode event) {
        String externalId = text(event, "id");
        if (externalId == null) {
            throw new IllegalStateException("Google accepted the event but returned no id.");
        }
        return new CreatedEvent(externalId, meetingUrl(event));
    }

    @Override
    public void deleteEvent(CoachCalendarConnection connection, String externalId) {
        http.delete()
                .uri(CALENDAR_API + "/calendars/{calendarId}/events/{eventId}?sendUpdates=all",
                        calendarId(connection), externalId)
                .header("Authorization", "Bearer " + accessToken(connection))
                .retrieve()
                // Already gone (404) or already cancelled (410) IS the state we wanted.
                .onStatus(status -> status.value() == 404 || status.value() == 410,
                        (request, response) -> { })
                .toBodilessEntity();
    }

    @Override
    public List<TimeRange> busy(CoachCalendarConnection connection, Instant from, Instant to) {
        String calendarId = calendarId(connection);
        JsonNode response = http.post()
                .uri(CALENDAR_API + "/freeBusy")
                .header("Authorization", "Bearer " + accessToken(connection))
                .contentType(MediaType.APPLICATION_JSON)
                .body(Map.of(
                        "timeMin", from.toString(),
                        "timeMax", to.toString(),
                        "items", List.of(Map.of("id", calendarId))))
                .retrieve()
                .body(JsonNode.class);

        JsonNode calendar = response == null
                ? null
                : response.path("calendars").path(calendarId);
        if (calendar == null || calendar.isMissingNode()) {
            return List.of();
        }
        JsonNode errors = calendar.path("errors");
        if (errors.isArray() && !errors.isEmpty()) {
            throw new IllegalStateException("Google could not read the calendar free/busy: " + errors);
        }
        List<TimeRange> busy = new ArrayList<>();
        for (JsonNode interval : calendar.path("busy")) {
            String start = text(interval, "start");
            String end = text(interval, "end");
            if (start != null && end != null) {
                busy.add(new TimeRange(Instant.parse(start), Instant.parse(end)));
            }
        }
        return busy;
    }

    @Override
    public void revoke(CoachCalendarConnection connection) {
        accessTokens.remove(connection.getUserId());
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("token", secrets.decrypt(connection.getRefreshTokenEnc()));
        postForm(REVOKE_ENDPOINT, form);
    }

    /* ------------------------------------------------------------------ tokens */

    private String accessToken(CoachCalendarConnection connection) {
        CachedToken cached = accessTokens.get(connection.getUserId());
        if (cached != null && cached.expiresAt().isAfter(Instant.now())) {
            return cached.token();
        }
        MultiValueMap<String, String> form = new LinkedMultiValueMap<>();
        form.add("grant_type", "refresh_token");
        form.add("refresh_token", secrets.decrypt(connection.getRefreshTokenEnc()));
        form.add("client_id", clientId);
        form.add("client_secret", clientSecret);

        JsonNode response;
        try {
            response = postForm(TOKEN_ENDPOINT, form);
        } catch (RuntimeException e) {
            accessTokens.remove(connection.getUserId());
            // invalid_grant means the coach revoked us, changed their password, or the
            // grant simply aged out. It is not retryable and it is not an outage — say
            // so plainly, because this message is what the coach reads next to
            // "Reconnect" on /app/team.
            if (String.valueOf(e.getMessage()).contains("invalid_grant")) {
                throw new IllegalStateException(
                        "Google no longer accepts the stored authorisation (invalid_grant). "
                                + "Reconnect the calendar.", e);
            }
            throw e;
        }

        String token = text(response, "access_token");
        if (token == null) {
            throw new IllegalStateException("Google returned no access token for the stored grant.");
        }
        long expiresIn = response.path("expires_in").asLong(3600L);
        Instant expiresAt = Instant.now()
                .plusSeconds(Math.max(expiresIn - EXPIRY_MARGIN.toSeconds(), 30L));
        accessTokens.put(connection.getUserId(), new CachedToken(token, expiresAt));
        return token;
    }

    /* ------------------------------------------------------------------ helpers */

    private JsonNode postForm(String url, MultiValueMap<String, String> form) {
        return http.post()
                .uri(url)
                .contentType(MediaType.APPLICATION_FORM_URLENCODED)
                .body(form)
                .retrieve()
                .body(JsonNode.class);
    }

    private static String calendarId(CoachCalendarConnection connection) {
        String id = connection.getCalendarId();
        return id == null || id.isBlank() ? CoachCalendarConnection.DEFAULT_CALENDAR_ID : id;
    }

    /**
     * {@code hangoutLink} is the plain answer; the entry points are the fallback
     * for the case where Google fills conferenceData but not the legacy field.
     * Null is a legitimate outcome — a Workspace policy can forbid Meet creation,
     * and the session is still a real event on a real calendar.
     */
    private static String meetingUrl(JsonNode event) {
        String hangout = text(event, "hangoutLink");
        if (hangout != null) {
            return hangout;
        }
        for (JsonNode entry : event.path("conferenceData").path("entryPoints")) {
            if ("video".equals(text(entry, "entryPointType"))) {
                return text(entry, "uri");
            }
        }
        return null;
    }

    /**
     * The id_token arrives over a TLS back channel from Google's token endpoint,
     * authenticated by our client secret, so the payload is read without a JWKS
     * signature check — exactly as {@code OAuth2Controller} does for SSO login.
     * Nothing here grants access: it only LABELS the connection so the coach can
     * see which account they linked.
     */
    private static String emailFromIdToken(String idToken) {
        if (idToken == null) {
            return null;
        }
        String[] parts = idToken.split("\\.");
        if (parts.length < 2) {
            return null;
        }
        try {
            JsonNode claims = JSON.readTree(Base64.getUrlDecoder().decode(parts[1]));
            return text(claims, "email");
        } catch (Exception e) {
            log.warn("Could not read the Google id_token payload: {}", e.getMessage());
            return null;
        }
    }

    private static String text(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode value = node.path(field);
        return value.isString() && !value.asString().isBlank() ? value.asString() : null;
    }

    private static String enc(String value) {
        return URLEncoder.encode(value == null ? "" : value, StandardCharsets.UTF_8);
    }

    private record CachedToken(String token, Instant expiresAt) {}
}
