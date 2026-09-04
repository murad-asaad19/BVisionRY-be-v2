package com.bvisionry.calendar.web;

import java.util.UUID;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.calendar.CalendarConnectionService;
import com.bvisionry.calendar.domain.CoachCalendarConnection;
import com.bvisionry.calendar.provider.CalendarProvider;
import com.bvisionry.calendar.provider.CalendarProviders;
import com.bvisionry.calendar.provider.GoogleCalendarProvider;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.config.FrontendUrls;

import lombok.extern.slf4j.Slf4j;

/**
 * Connect, inspect and disconnect the coach's own calendar (sessions spec v2
 * §7). One connection per coach, and the coach is always the connection: every
 * route below reads the principal, never a path or body id, so there is no
 * shape of request that can name someone else's calendar.
 *
 * <p>The callback is the exception, and it has to be: Google redirects the bare
 * browser here with no Authorization header, so it is {@code permitAll()} at
 * both layers ({@code SecurityConfig} for the route, {@code @PreAuthorize} here
 * overriding the class-level COACH gate) and the identity comes from the
 * HMAC-signed {@code state} instead — see {@link CalendarOAuthState}.
 */
@RestController
@RequestMapping("/api/v1/coach/calendar")
@PreAuthorize("hasAuthority('COACH')")
@Slf4j
public class CoachCalendarController {

    private static final String CALLBACK_PATH = "/api/v1/coach/calendar/google/callback";

    /** Where the coach lands afterwards — the page that holds the Calendar card. */
    private static final String RETURN_PATH = "/app/team?calendar=";

    private final CalendarConnectionService connections;
    private final GoogleCalendarProvider google;
    private final CalendarProviders providers;
    private final CalendarOAuthState state;
    private final CurrentUserAccessor currentUser;
    private final FrontendUrls frontendUrls;

    /** Where GOOGLE sends the browser back — our own origin, not the web app's. */
    private final String redirectUri;

    public CoachCalendarController(CalendarConnectionService connections,
                                   GoogleCalendarProvider google,
                                   CalendarProviders providers,
                                   CalendarOAuthState state,
                                   CurrentUserAccessor currentUser,
                                   FrontendUrls frontendUrls,
                                   @Value("${bvisionry.oauth2.redirect-base-url:http://localhost:8080}")
                                   String redirectBaseUrl) {
        this.connections = connections;
        this.google = google;
        this.providers = providers;
        this.state = state;
        this.currentUser = currentUser;
        this.frontendUrls = frontendUrls;
        this.redirectUri = redirectBaseUrl + CALLBACK_PATH;
    }

    @GetMapping
    public CalendarConnectionDto connection() {
        return connections.find(currentUser.require().userId())
                .map(CalendarConnectionDto::of)
                .orElse(CalendarConnectionDto.NOT_CONNECTED);
    }

    /**
     * The consent URL for the caller's own calendar. Returned rather than
     * redirected: the web app calls this through its BFF, so a 302 would be
     * followed server-side and the coach would never see the consent screen.
     */
    @PostMapping("/connect/google")
    public ConnectUrlResponse connectGoogle() {
        if (!google.isConfigured()) {
            throw new BadRequestException("Google Calendar is not configured on this server.");
        }
        UUID userId = currentUser.require().userId();
        return new ConnectUrlResponse(google.authorizationUrl(state.sign(userId), redirectUri));
    }

    /**
     * Google's redirect. Every failure — a denied consent, a tampered or expired
     * state, a refused exchange — lands the coach back on the same card with
     * {@code ?calendar=error}: there is no session here to raise a 4xx into, and
     * a raw error page mid-OAuth tells them nothing they can act on.
     */
    @PreAuthorize("permitAll()")
    @GetMapping("/google/callback")
    public ResponseEntity<Void> googleCallback(@RequestParam(required = false) String code,
                                               @RequestParam(required = false) String state,
                                               @RequestParam(required = false) String error) {
        if (error != null || code == null) {
            return back("error");
        }
        UUID userId = this.state.verify(state).orElse(null);
        if (userId == null) {
            log.warn("Calendar callback rejected: state missing, expired or tampered with");
            return back("error");
        }
        try {
            CalendarProvider.Grant grant = google.exchange(code, redirectUri);
            connections.connect(userId, GoogleCalendarProvider.ID, grant);
            return back("connected");
        } catch (RuntimeException e) {
            log.warn("Calendar connect failed for user {}: {}", userId, e.getMessage());
            return back("error");
        }
    }

    /**
     * Disconnect. The grant is handed back to Google first, best effort — a
     * revoke that fails must not leave the coach stuck with a connection they
     * asked to remove, and the row is the thing that makes us USE the grant.
     * Events already on their calendar are deliberately left alone: they are
     * real meetings with real guests, not our bookkeeping.
     */
    @DeleteMapping
    public ResponseEntity<Void> disconnect() {
        UUID userId = currentUser.require().userId();
        connections.find(userId).ifPresent(this::revokeQuietly);
        connections.disconnect(userId);
        return ResponseEntity.noContent().build();
    }

    private void revokeQuietly(CoachCalendarConnection connection) {
        try {
            providers.byId(connection.getProvider())
                    .ifPresent(provider -> provider.revoke(connection));
        } catch (RuntimeException e) {
            log.warn("Revoking the calendar grant for user {} failed: {}",
                    connection.getUserId(), e.getMessage());
        }
    }

    private ResponseEntity<Void> back(String outcome) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .header(HttpHeaders.LOCATION, frontendUrls.path(RETURN_PATH + outcome))
                .build();
    }

    /** The URL the coach's browser must be sent to for consent. */
    public record ConnectUrlResponse(String url) {}
}
