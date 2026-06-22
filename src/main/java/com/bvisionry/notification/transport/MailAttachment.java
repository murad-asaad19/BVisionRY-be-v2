package com.bvisionry.notification.transport;

/**
 * A single file attached to an outgoing email. Transport-agnostic: SMTP wraps
 * the bytes in a {@code MimeBodyPart}, the Resend HTTP API base64-encodes them.
 *
 * @param fileName    the name the recipient sees, e.g. {@code "founder-readiness.pdf"}
 * @param contentType MIME type, e.g. {@code "application/pdf"}
 * @param content     the raw file bytes
 */
public record MailAttachment(String fileName, String contentType, byte[] content) {
}
