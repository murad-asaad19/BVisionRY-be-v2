package com.bvisionry.notification.transport;

import java.util.List;

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
     * Sends a single email. Convenience overload that delegates to
     * {@link #send(String, String, String, String, List)} with no attachments.
     *
     * @param to        recipient address
     * @param subject   subject line (already rendered, no template placeholders)
     * @param htmlBody  HTML body (already rendered)
     * @param replyTo   Reply-To address, or {@code null}/blank to omit the header.
     *                  Used by the contact form so admins can reply straight to
     *                  the visitor (the From stays the configured platform address).
     */
    default void send(String to, String subject, String htmlBody, String replyTo) {
        send(to, subject, htmlBody, replyTo, List.of());
    }

    /**
     * Sends a single email, optionally with file attachments. Implementations
     * must throw {@link com.bvisionry.common.exception.EmailDeliveryException}
     * on failure so the caller can surface a uniform error to the API client.
     *
     * @param to          recipient address
     * @param subject     subject line (already rendered, no template placeholders)
     * @param htmlBody    HTML body (already rendered)
     * @param replyTo     Reply-To address, or {@code null}/blank to omit the header
     * @param attachments files to attach; empty for the common no-attachment case.
     *                    Used by the lead-magnet flow to deliver the research PDF.
     */
    void send(String to, String subject, String htmlBody, String replyTo, List<MailAttachment> attachments);
}
