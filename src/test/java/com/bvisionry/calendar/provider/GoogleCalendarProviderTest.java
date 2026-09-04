package com.bvisionry.calendar.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.jsonPath;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.ExpectedCount;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.bvisionry.calendar.domain.CoachCalendarConnection;
import com.bvisionry.common.calendar.TimeRange;
import com.bvisionry.common.crypto.SecretEncryptionService;

/**
 * The wire contract with Google, pinned against a mock server.
 *
 * <p>Everything asserted here is something the API silently tolerates and then
 * does the wrong thing with: drop {@code conferenceDataVersion=1} and the
 * conference block is ignored (no Meet link, no error); drop {@code sendUpdates}
 * and the founders are never invited; read the wrong response field and the
 * link is null. None of it fails loudly in production, so it is pinned here.
 */
class GoogleCalendarProviderTest {

    private static final String TOKEN_URL = "https://oauth2.googleapis.com/token";
    private static final String EVENTS_URL =
            "https://www.googleapis.com/calendar/v3/calendars/primary/events"
                    + "?conferenceDataVersion=1&sendUpdates=all";
    private static final String FREEBUSY_URL = "https://www.googleapis.com/calendar/v3/freeBusy";
    private static final String PATCH_URL =
            "https://www.googleapis.com/calendar/v3/calendars/primary/events/evt-123"
                    + "?conferenceDataVersion=1&sendUpdates=all";

    /** 32 bytes of hex — SecretEncryptionService validates the alphabet, not just the length. */
    private static final String KEY = "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef";

    private MockRestServiceServer server;
    private GoogleCalendarProvider provider;
    private SecretEncryptionService secrets;
    private CoachCalendarConnection connection;

    @BeforeEach
    void setUp() {
        RestClient.Builder builder = RestClient.builder();
        server = MockRestServiceServer.bindTo(builder).build();
        secrets = new SecretEncryptionService(KEY);
        provider = new GoogleCalendarProvider(builder, secrets, "client-id", "client-secret");

        connection = new CoachCalendarConnection();
        connection.setUserId(UUID.randomUUID());
        connection.setProvider(GoogleCalendarProvider.ID);
        connection.setAccountEmail("coach@example.com");
        connection.setRefreshTokenEnc(secrets.encrypt("stored-refresh-token"));
        connection.setCalendarId("primary");
    }

    @Test
    @DisplayName("the consent URL asks for offline access, a fresh consent and the two calendar scopes")
    void theAuthorizationUrlAsksForARefreshToken() {
        String url = provider.authorizationUrl("state-token", "https://api.test/callback");

        assertThat(url).startsWith("https://accounts.google.com/o/oauth2/v2/auth?");
        assertThat(url).contains("access_type=offline").contains("prompt=consent");
        assertThat(url).contains("state=state-token");
        assertThat(url).contains(enc("https://www.googleapis.com/auth/calendar.events"));
        assertThat(url).contains(enc("https://www.googleapis.com/auth/calendar.freebusy"));
        assertThat(url).contains(enc("https://api.test/callback"));
    }

    @Test
    @DisplayName("exchange trades the code for the refresh token and reads the account from the id_token")
    void exchangeReturnsTheGrant() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=authorization_code")))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("code=auth-code")))
                .andRespond(withSuccess("""
                        {"refresh_token":"the-refresh-token","access_token":"at","expires_in":3600,
                         "id_token":"%s"}
                        """.formatted(idToken("coach@example.com")), MediaType.APPLICATION_JSON));

        CalendarProvider.Grant grant = provider.exchange("auth-code", "https://api.test/callback");

        assertThat(grant.refreshToken()).isEqualTo("the-refresh-token");
        assertThat(grant.accountEmail()).isEqualTo("coach@example.com");
        server.verify();
    }

    @Test
    @DisplayName("a consent that yields no refresh token is refused, not stored as a half-connection")
    void exchangeWithoutARefreshTokenFails() {
        server.expect(requestTo(TOKEN_URL))
                .andRespond(withSuccess("{\"access_token\":\"at\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.exchange("auth-code", "https://api.test/callback"))
                .hasMessageContaining("no refresh token");
    }

    @Test
    @DisplayName("createEvent posts the attendees and the Meet create-request, and reads back hangoutLink")
    void createEventRequestsAMeetRoomAndReadsTheLink() {
        expectTokenRefresh();
        server.expect(requestTo(EVENTS_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(header("Authorization", "Bearer fresh-access-token"))
                .andExpect(jsonPath("$.summary").value("Group coaching — Spring Cohort"))
                .andExpect(jsonPath("$.attendees[0].email").value("founder@example.com"))
                .andExpect(jsonPath("$.attendees[1].email").value("other@example.com"))
                .andExpect(jsonPath("$.conferenceData.createRequest.requestId").value("session-1"))
                .andExpect(jsonPath("$.conferenceData.createRequest.conferenceSolutionKey.type")
                        .value("hangoutsMeet"))
                .andRespond(withSuccess("""
                        {"id":"evt-123","hangoutLink":"https://meet.google.com/abc-defg-hij"}
                        """, MediaType.APPLICATION_JSON));

        CalendarProvider.CreatedEvent created = provider.createEvent(connection, spec());

        assertThat(created.externalId()).isEqualTo("evt-123");
        assertThat(created.meetingUrl()).isEqualTo("https://meet.google.com/abc-defg-hij");
        server.verify();
    }

    @Test
    @DisplayName("with no hangoutLink the video entry point is used instead")
    void createEventFallsBackToTheVideoEntryPoint() {
        expectTokenRefresh();
        server.expect(requestTo(EVENTS_URL)).andRespond(withSuccess("""
                {"id":"evt-9","conferenceData":{"entryPoints":[
                   {"entryPointType":"phone","uri":"tel:+1"},
                   {"entryPointType":"video","uri":"https://meet.google.com/xyz"}]}}
                """, MediaType.APPLICATION_JSON));

        assertThat(provider.createEvent(connection, spec()).meetingUrl())
                .isEqualTo("https://meet.google.com/xyz");
    }

    @Test
    @DisplayName("updateEvent PATCHES the times and guests, and sends NO createRequest")
    void updateEventPatchesWithoutMintingASecondMeetRoom() {
        expectTokenRefresh();
        server.expect(requestTo(PATCH_URL))
                .andExpect(method(HttpMethod.PATCH))
                .andExpect(header("Authorization", "Bearer fresh-access-token"))
                .andExpect(jsonPath("$.summary").value("Group coaching \u2014 Spring Cohort"))
                .andExpect(jsonPath("$.start.dateTime").value("2026-09-14T10:00:00Z"))
                .andExpect(jsonPath("$.end.dateTime").value("2026-09-14T10:45:00Z"))
                .andExpect(jsonPath("$.attendees[0].email").value("founder@example.com"))
                // A createRequest on a PATCH would mint a SECOND Meet room and
                // strand the link every guest already has.
                .andExpect(jsonPath("$.conferenceData").doesNotExist())
                .andRespond(withSuccess("""
                        {"id":"evt-123","hangoutLink":"https://meet.google.com/abc-defg-hij"}
                        """, MediaType.APPLICATION_JSON));

        CalendarProvider.CreatedEvent moved = provider.updateEvent(connection, "evt-123", spec());

        assertThat(moved.externalId()).isEqualTo("evt-123");
        assertThat(moved.meetingUrl()).isEqualTo("https://meet.google.com/abc-defg-hij");
        server.verify();
    }

    @Test
    @DisplayName("patching an event Google no longer has says so, so the caller can create one")
    void updatingAMissingEventIsReportedAsNotFound() {
        expectTokenRefresh();
        server.expect(requestTo(PATCH_URL)).andRespond(withStatus(HttpStatus.GONE));

        assertThatThrownBy(() -> provider.updateEvent(connection, "evt-123", spec()))
                .isInstanceOf(CalendarProvider.EventNotFound.class);
    }

    @Test
    @DisplayName("an event Google already deleted is a success, not a failure to report")
    void deletingAMissingEventIsTolerated() {
        expectTokenRefresh();
        server.expect(requestTo("https://www.googleapis.com/calendar/v3/calendars/primary/events/evt-1"
                        + "?sendUpdates=all"))
                .andExpect(method(HttpMethod.DELETE))
                .andRespond(withStatus(HttpStatus.NOT_FOUND));

        provider.deleteEvent(connection, "evt-1");
        server.verify();
    }

    @Test
    @DisplayName("freeBusy returns the coach's busy intervals")
    void busyParsesTheIntervals() {
        expectTokenRefresh();
        server.expect(requestTo(FREEBUSY_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(jsonPath("$.items[0].id").value("primary"))
                .andRespond(withSuccess("""
                        {"calendars":{"primary":{"busy":[
                          {"start":"2026-09-14T09:00:00Z","end":"2026-09-14T10:00:00Z"},
                          {"start":"2026-09-14T13:00:00Z","end":"2026-09-14T13:30:00Z"}]}}}
                        """, MediaType.APPLICATION_JSON));

        List<TimeRange> busy = provider.busy(connection,
                Instant.parse("2026-09-14T00:00:00Z"), Instant.parse("2026-09-15T00:00:00Z"));

        assertThat(busy).containsExactly(
                new TimeRange(Instant.parse("2026-09-14T09:00:00Z"), Instant.parse("2026-09-14T10:00:00Z")),
                new TimeRange(Instant.parse("2026-09-14T13:00:00Z"), Instant.parse("2026-09-14T13:30:00Z")));
        server.verify();
    }

    @Test
    @DisplayName("a per-calendar error is raised, not silently read as 'never busy'")
    void busyRaisesAPerCalendarError() {
        expectTokenRefresh();
        server.expect(requestTo(FREEBUSY_URL)).andRespond(withSuccess(
                "{\"calendars\":{\"primary\":{\"errors\":[{\"reason\":\"notFound\"}],\"busy\":[]}}}",
                MediaType.APPLICATION_JSON));

        assertThatThrownBy(() -> provider.busy(connection, Instant.now(), Instant.now().plusSeconds(60)))
                .hasMessageContaining("notFound");
    }

    @Test
    @DisplayName("the access token is minted once and reused until it nears expiry")
    void theAccessTokenIsCached() {
        expectTokenRefresh();
        server.expect(ExpectedCount.twice(), requestTo(EVENTS_URL))
                .andExpect(header("Authorization", "Bearer fresh-access-token"))
                .andRespond(withSuccess("{\"id\":\"evt\"}", MediaType.APPLICATION_JSON));

        provider.createEvent(connection, spec());
        provider.createEvent(connection, spec());

        // Exactly one token call for two events: the second createEvent reused the cache.
        server.verify();
    }

    @Test
    @DisplayName("a refused refresh token is reported as 'reconnect', not as a generic outage")
    void anInvalidGrantSaysReconnect() {
        server.expect(requestTo(TOKEN_URL)).andRespond(withStatus(HttpStatus.BAD_REQUEST)
                .contentType(MediaType.APPLICATION_JSON)
                .body("{\"error\":\"invalid_grant\"}"));

        assertThatThrownBy(() -> provider.createEvent(connection, spec()))
                .hasMessageContaining("invalid_grant")
                .hasMessageContaining("Reconnect");
    }

    @Test
    @DisplayName("revoke hands the DECRYPTED refresh token back to Google")
    void revokePostsThePlaintextGrant() {
        server.expect(requestTo("https://oauth2.googleapis.com/revoke"))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string("token=stored-refresh-token"))
                .andRespond(withSuccess("{}", MediaType.APPLICATION_JSON));

        provider.revoke(connection);
        server.verify();
    }

    /* --------------------------------------------------------------- helpers */

    private void expectTokenRefresh() {
        server.expect(requestTo(TOKEN_URL))
                .andExpect(method(HttpMethod.POST))
                .andExpect(content().string(org.hamcrest.Matchers.containsString("grant_type=refresh_token")))
                .andExpect(content().string(
                        org.hamcrest.Matchers.containsString("refresh_token=stored-refresh-token")))
                .andRespond(withSuccess(
                        "{\"access_token\":\"fresh-access-token\",\"expires_in\":3600}",
                        MediaType.APPLICATION_JSON));
    }

    private static CalendarProvider.EventSpec spec() {
        return new CalendarProvider.EventSpec(
                "Group coaching — Spring Cohort",
                "Your Bvisionry session: https://app.test/app/program/tasks/t/session",
                Instant.parse("2026-09-14T10:00:00Z"),
                Instant.parse("2026-09-14T10:45:00Z"),
                List.of("founder@example.com", "other@example.com"),
                "session-1");
    }

    /** A JWS-shaped string whose payload is all this code reads. */
    private static String idToken(String email) {
        String payload = Base64.getUrlEncoder().withoutPadding().encodeToString(
                ("{\"email\":\"" + email + "\"}").getBytes(StandardCharsets.UTF_8));
        return "header." + payload + ".signature";
    }

    private static String enc(String value) {
        return java.net.URLEncoder.encode(value, StandardCharsets.UTF_8);
    }
}
