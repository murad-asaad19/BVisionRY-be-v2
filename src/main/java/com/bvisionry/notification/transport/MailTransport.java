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
     * Sends a single email with no Reply-To header. Convenience overload for the
     * common case; delegates to {@link #send(String, String, String, String)}.
     *
     * @param to        recipient address
     * @param subject   subject line (already rendered, no template placeholders)
     * @param htmlBody  HTML body (already rendered)
     */
    default void send(String to, String subject, String htmlBody) {
        send(to, subject, htmlBody, null);
    }

    /**
     * Sends a single email. Implementations must throw
     * {@link com.bvisionry.common.exception.EmailDeliveryException} on failure
     * so the caller can surface a uniform error to the API client.
     *
     * @param to        recipient address
     * @param subject   subject line (already rendered, no template placeholders)
     * @param htmlBody  HTML body (already rendered)
     * @param replyTo   Reply-To address, or {@code null}/blank to omit the header.
     *                  Used by the contact form so admins can reply straight to
     *                  the visitor (the From stays the configured platform address).
     */
    void send(String to, String subject, String htmlBody, String replyTo);
}
