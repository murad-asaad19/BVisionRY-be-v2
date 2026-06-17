package com.bvisionry.notification.transport;

import com.bvisionry.common.exception.EmailDeliveryException;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.List;
import java.util.Map;

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
                .defaultHeader("Authorization", "Bearer " + apiKey)
                .defaultHeader("Content-Type", MediaType.APPLICATION_JSON_VALUE)
                .build();
    }

    @Override
    public void send(String to, String subject, String htmlBody, String replyTo) {
        Map<String, Object> payload = new java.util.HashMap<>();
        payload.put("from", fromAddress);
        payload.put("to", List.of(to));
        payload.put("subject", subject);
        payload.put("html", htmlBody);
        // Resend accepts reply_to as a string or array; a single validated address suffices.
        if (replyTo != null && !replyTo.isBlank()) {
            payload.put("reply_to", replyTo);
        }

        try {
            Map<String, Object> response = restClient.post()
                    .uri("/emails")
                    .body(payload)
                    .retrieve()
                    .body(new org.springframework.core.ParameterizedTypeReference<Map<String, Object>>() {});

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
