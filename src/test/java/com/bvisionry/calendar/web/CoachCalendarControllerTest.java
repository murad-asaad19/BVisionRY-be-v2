package com.bvisionry.calendar.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import com.bvisionry.calendar.CalendarConnectionService;
import com.bvisionry.calendar.domain.CoachCalendarConnection;
import com.bvisionry.calendar.provider.CalendarProvider;
import com.bvisionry.calendar.provider.CalendarProviders;
import com.bvisionry.calendar.provider.GoogleCalendarProvider;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.config.FrontendProperties;
import com.bvisionry.config.FrontendUrls;

/**
 * The connect handshake, and specifically the two ways it must refuse: an
 * unconfigured server (so the coach is told, not silently sent to a broken
 * consent screen) and a state token that did not come from us.
 *
 * <p>The state check is the whole security of the callback. It is
 * unauthenticated by necessity — Google redirects a bare browser — so a
 * forgeable state would let anyone bind THEIR Google calendar to SOMEONE ELSE'S
 * coach account, and every future session of that coach would then be created
 * on the attacker's calendar with the founders as guests.
 */
class CoachCalendarControllerTest {

    private static final String JWT_SECRET = "test-secret-for-calendar-state-tokens-32-bytes-plus";
    private static final UUID COACH = UUID.randomUUID();

    private CalendarConnectionService connections;
    private GoogleCalendarProvider google;
    private CalendarOAuthState state;
    private CoachCalendarController controller;

    @BeforeEach
    void setUp() {
        connections = mock(CalendarConnectionService.class);
        google = mock(GoogleCalendarProvider.class);
        when(google.id()).thenReturn(GoogleCalendarProvider.ID);
        state = new CalendarOAuthState(JWT_SECRET);

        FrontendProperties properties = new FrontendProperties();
        properties.setBaseUrl("https://app.test");

        CurrentUserAccessor currentUser = () -> new CurrentUser(COACH, null, "Coach", "COACH");
        controller = new CoachCalendarController(connections, google,
                new CalendarProviders(List.of(google)), state, currentUser,
                new FrontendUrls(properties), "https://api.test");
    }

    @Test
    @DisplayName("a server with no Google client says so instead of offering a dead button")
    void connectRefusesWhenGoogleIsNotConfigured() {
        when(google.isConfigured()).thenReturn(false);

        assertThatThrownBy(() -> controller.connectGoogle())
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("not configured");
    }

    @Test
    @DisplayName("connect returns a consent URL carrying the callback and a state naming the caller")
    void connectReturnsTheConsentUrl() {
        when(google.isConfigured()).thenReturn(true);
        when(google.authorizationUrl(anyString(), anyString()))
                .thenAnswer(call -> "https://consent.test/?state=" + call.getArgument(0)
                        + "&redirect_uri=" + call.getArgument(1));

        String url = controller.connectGoogle().url();

        assertThat(url).contains("redirect_uri=https://api.test/api/v1/coach/calendar/google/callback");
        String signed = url.substring(url.indexOf("state=") + 6, url.indexOf("&redirect_uri"));
        assertThat(state.verify(signed)).contains(COACH);
    }

    @Test
    @DisplayName("the connection read is the caller's own, with the last error included")
    void connectionReportsTheStoredRow() {
        CoachCalendarConnection stored = new CoachCalendarConnection();
        stored.setUserId(COACH);
        stored.setProvider(GoogleCalendarProvider.ID);
        stored.setAccountEmail("coach@example.com");
        stored.setConnectedAt(OffsetDateTime.now());
        stored.setLastError("invalid_grant");
        when(connections.find(COACH)).thenReturn(Optional.of(stored));

        CalendarConnectionDto dto = controller.connection();

        assertThat(dto.connected()).isTrue();
        assertThat(dto.accountEmail()).isEqualTo("coach@example.com");
        assertThat(dto.lastError()).isEqualTo("invalid_grant");
    }

    @Test
    @DisplayName("no row reads as not connected rather than as an error")
    void connectionReportsNotConnected() {
        when(connections.find(COACH)).thenReturn(Optional.empty());

        assertThat(controller.connection().connected()).isFalse();
        assertThat(controller.connection().provider()).isNull();
    }

    @Test
    @DisplayName("a valid callback exchanges the code and stores the grant for the state's coach")
    void aValidCallbackStoresTheGrant() {
        CalendarProvider.Grant grant = new CalendarProvider.Grant("refresh", "coach@example.com");
        when(google.exchange(eq("the-code"), anyString())).thenReturn(grant);

        ResponseEntity<Void> response = controller.googleCallback("the-code", state.sign(COACH), null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.FOUND);
        assertThat(location(response)).isEqualTo("https://app.test/app/team?calendar=connected");
        verify(connections).connect(COACH, GoogleCalendarProvider.ID, grant);
    }

    @Test
    @DisplayName("a TAMPERED state never reaches the exchange and never binds a calendar")
    void aTamperedStateIsRefused() {
        String tampered = tamper(state.sign(COACH));

        ResponseEntity<Void> response = controller.googleCallback("the-code", tampered, null);

        assertThat(location(response)).isEqualTo("https://app.test/app/team?calendar=error");
        verify(google, never()).exchange(anyString(), anyString());
        verify(connections, never()).connect(any(), anyString(), any());
    }

    @Test
    @DisplayName("a denied consent and a missing code both land back on the card with an error")
    void aDeniedConsentIsAnErrorRedirect() {
        assertThat(location(controller.googleCallback(null, null, "access_denied")))
                .endsWith("?calendar=error");
        assertThat(location(controller.googleCallback(null, state.sign(COACH), null)))
                .endsWith("?calendar=error");
        verify(connections, never()).connect(any(), anyString(), any());
    }

    @Test
    @DisplayName("disconnect removes the row even when revoking the grant fails")
    void disconnectAlwaysRemovesTheRow() {
        CoachCalendarConnection stored = new CoachCalendarConnection();
        stored.setUserId(COACH);
        stored.setProvider(GoogleCalendarProvider.ID);
        when(connections.find(COACH)).thenReturn(Optional.of(stored));
        doThrow(new IllegalStateException("Google said no")).when(google).revoke(stored);

        ResponseEntity<Void> response = controller.disconnect();

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.NO_CONTENT);
        verify(connections).disconnect(COACH);
    }

    /* --------------------------------------------------------------- helpers */

    private static String location(ResponseEntity<Void> response) {
        return URLDecoder.decode(response.getHeaders().getFirst(HttpHeaders.LOCATION),
                StandardCharsets.UTF_8);
    }

    /** Flip one character of the payload so the signature no longer matches. */
    private static String tamper(String token) {
        String[] parts = token.split("\\.");
        char[] payload = parts[1].toCharArray();
        payload[0] = payload[0] == 'e' ? 'f' : 'e';
        return parts[0] + "." + new String(payload) + "." + parts[2];
    }
}
