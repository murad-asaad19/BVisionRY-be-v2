package com.bvisionry.notification.transport;

import com.bvisionry.common.exception.EmailDeliveryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;
import org.springframework.web.client.RestClientResponseException;

import java.time.Duration;
import java.time.ZonedDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Sends email via Resend's HTTPS REST API ({@code POST /emails}). Used in
 * environments where outbound SMTP is blocked (Railway, Heroku, Render, etc.).
 *
 * Activated when {@code bvisionry.mail.transport=resend-http}.
 *
 * Docs: https://resend.com/docs/api-reference/emails/send-email
 */
@Component
@ConditionalOnProperty(name = "bvisionry.mail.transport", havingValue = "resend-http")
@Slf4j
public class ResendHttpMailTransport implements MailTransport {

    private static final String RESEND_API_URL = "https://api.resend.com";

    /** Bound the connect/read phases so a hung Resend connection can never pin the calling thread. */
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration READ_TIMEOUT = Duration.ofSeconds(10);

    /** Total attempts (1 initial + 2 retries) on transient failures (connect/read timeouts, 5xx, 429). */
    private static final int MAX_ATTEMPTS = 3;
    /** Base for exponential backoff; attempt N waits ~BASE * 2^(N-1) plus jitter. */
    private static final Duration BACKOFF_BASE = Duration.ofMillis(500);
    /** Never park a thread longer than this, even when honoring a large {@code Retry-After}. */
    private static final Duration MAX_BACKOFF = Duration.ofSeconds(30);

    private final RestClient restClient;
    private final String fromAddress;

    public ResendHttpMailTransport(RestClient.Builder restClientBuilder,
                                    @Value("${bvisionry.mail.resend.api-key:}") String apiKey,
                                    @Value("${bvisionry.mail.from:noreply@bvisionry.com}") String fromAddress) {
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalStateException(
                    "bvisionry.mail.resend.api-key is required when bvisionry.mail.transport=resend-http " +
                    "(set the RESEND_API_KEY environment variable).");
        }
        this.fromAddress = fromAddress;
        // Clone the shared builder so our baseUrl/Authorization header don't leak
        // into other RestClient consumers (e.g. OAuth2Controller).
        this.restClient = restClientBuilder.clone()
                .baseUrl(RESEND_API_URL)
                .requestFactory(buildRequestFactory())
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    private static SimpleClientHttpRequestFactory buildRequestFactory() {
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout(CONNECT_TIMEOUT);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }

    @Override
    public void send(String to, String subject, String htmlBody, String replyTo,
                     List<MailAttachment> attachments) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("from", fromAddress);
        payload.put("to", List.of(to));
        payload.put("subject", subject);
        payload.put("html", htmlBody);
        // Resend accepts reply_to as a string or array; a single validated address suffices.
        if (replyTo != null && !replyTo.isBlank()) {
            payload.put("reply_to", replyTo);
        }
        // Resend's REST API takes attachments as { filename, content (base64), content_type }.
        if (attachments != null && !attachments.isEmpty()) {
            List<Map<String, Object>> parts = new ArrayList<>(attachments.size());
            for (MailAttachment att : attachments) {
                parts.add(Map.of(
                        "filename", att.fileName(),
                        "content", Base64.getEncoder().encodeToString(att.content()),
                        "content_type", att.contentType()));
            }
            payload.put("attachments", parts);
        }

        try {
            Map<String, Object> response = postWithRetry(payload, to);
            String id = response != null ? String.valueOf(response.get("id")) : "<no-id>";
            log.info("Email sent via Resend HTTP to {} (id: {}) - subject: {}", to, id, subject);
        } catch (RestClientResponseException e) {
            String reason = extractResendError(e);
            log.error("Resend HTTP send to {} failed (status {}): {}", to, e.getStatusCode(), reason);
            throw new EmailDeliveryException("Email sending failed: " + reason);
        } catch (Exception e) {
            log.error("Resend HTTP send to {} failed: {}", to, e.getMessage());
            throw new EmailDeliveryException("Email sending failed. Check mail provider configuration and try again.");
        }
    }

    /**
     * Performs the {@code POST /emails} call with bounded retry on transient failures:
     * connect/read timeouts (surfaced as {@link RestClientException}), HTTP 5xx, and HTTP 429.
     * Backs off exponentially with jitter between attempts; for 429 (and 503 when present) the
     * server's {@code Retry-After} header is honored instead, capped at {@link #MAX_BACKOFF}.
     * Non-429 4xx responses are permanent and rethrown immediately without retry. After exhausting
     * {@link #MAX_ATTEMPTS}, the last failure is rethrown so the caller's catch logs it observably.
     */
    private Map<String, Object> postWithRetry(Map<String, Object> payload, String to) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_ATTEMPTS; attempt++) {
            try {
                return restClient.post()
                        .uri("/emails")
                        .body(payload)
                        .retrieve()
                        .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            } catch (RestClientResponseException e) {
                if (!isRetryableStatus(e.getStatusCode())) {
                    throw e; // permanent (bad request / auth / etc.) - do not retry
                }
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    Duration wait = retryAfter(e).orElseGet(() -> backoff(attempt));
                    log.warn("Resend HTTP send to {} transient failure (status {}), attempt {}/{}, retrying in {} ms",
                            to, e.getStatusCode(), attempt, MAX_ATTEMPTS, wait.toMillis());
                    sleep(wait);
                }
            } catch (RestClientException e) {
                // I/O level failure: connect/read timeout, connection reset, DNS, etc. - all transient.
                last = e;
                if (attempt < MAX_ATTEMPTS) {
                    Duration wait = backoff(attempt);
                    log.warn("Resend HTTP send to {} transient I/O failure ({}), attempt {}/{}, retrying in {} ms",
                            to, e.getMessage(), attempt, MAX_ATTEMPTS, wait.toMillis());
                    sleep(wait);
                }
            }
        }
        log.error("Resend HTTP send to {} exhausted {} attempts", to, MAX_ATTEMPTS);
        throw last; // rethrown into send()'s catch blocks, preserving the existing exception contract
    }

    /** Transient HTTP statuses worth retrying: any 5xx and 429 Too Many Requests. */
    private static boolean isRetryableStatus(HttpStatusCode status) {
        return status.is5xxServerError() || status.value() == 429;
    }

    /** Exponential backoff with full jitter, capped at {@link #MAX_BACKOFF}. */
    private static Duration backoff(int attempt) {
        long ceilMillis = Math.min(BACKOFF_BASE.toMillis() << (attempt - 1), MAX_BACKOFF.toMillis());
        return Duration.ofMillis(ThreadLocalRandom.current().nextLong(ceilMillis + 1));
    }

    /**
     * Parses a {@code Retry-After} header (delta-seconds or HTTP-date) into a capped wait.
     * Only meaningful on 429/503 responses; returns empty when absent or unparseable so the
     * caller falls back to {@link #backoff(int)}.
     */
    private static java.util.Optional<Duration> retryAfter(RestClientResponseException e) {
        HttpHeaders headers = e.getResponseHeaders();
        if (headers == null) {
            return java.util.Optional.empty();
        }
        String value = headers.getFirst(HttpHeaders.RETRY_AFTER);
        if (value == null || value.isBlank()) {
            return java.util.Optional.empty();
        }
        value = value.trim();
        Duration wait;
        try {
            wait = Duration.ofSeconds(Long.parseLong(value));
        } catch (NumberFormatException notSeconds) {
            try {
                wait = Duration.between(ZonedDateTime.now(), ZonedDateTime.parse(value,
                        java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME));
            } catch (DateTimeParseException notADate) {
                return java.util.Optional.empty();
            }
        }
        if (wait.isNegative()) {
            wait = Duration.ZERO;
        }
        if (wait.compareTo(MAX_BACKOFF) > 0) {
            wait = MAX_BACKOFF; // cap so a thread can't be parked for minutes
        }
        return java.util.Optional.of(wait);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration.toMillis());
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new EmailDeliveryException("Email sending interrupted while awaiting retry.");
        }
    }

    /**
     * Resend returns errors as {@code {"name": "...", "message": "..."}} (HTTP 4xx/5xx).
     * Fall back to the raw status text if the body isn't parseable.
     */
    private String extractResendError(RestClientResponseException e) {
        try {
            Map<String, Object> body = e.getResponseBodyAs(
                    new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});
            if (body != null) {
                Object message = body.get("message");
                if (message != null) return String.valueOf(message);
            }
        } catch (Exception ignored) {
            // fall through to status text
        }
        HttpStatusCode status = e.getStatusCode();
        return "HTTP " + status.value();
    }
}
