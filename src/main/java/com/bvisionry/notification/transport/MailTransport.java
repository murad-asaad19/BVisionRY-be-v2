package com.bvisionry.notification.transport;

/**
 * Delivers a fully-rendered email to its recipient. Implementations decide
 * the transport (SMTP, provider HTTP API, etc.); callers stay transport-agnostic.
 *
 * Selected at runtime via the {@code bvisionry.mail.transport} property so that
 * local dev can use SMTP (MailHog) while prod uses an HTTP API (Railway and
 * similar PaaS hosts block outbound SMTP).
 */
public interface MailTransport {

    /**
     * Sends a single email. Implementations must throw
     * {@link com.bvisionry.common.exception.EmailDeliveryException} on failure
     * so the caller can surface a uniform error to the API client.
     *
     * @param to        recipient address
     * @param subject   subject line (already rendered, no template placeholders)
     * @param htmlBody  HTML body (already rendered)
     */
    void send(String to, String subject, String htmlBody);
}
