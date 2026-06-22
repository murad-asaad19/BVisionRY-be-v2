package com.bvisionry.notification.transport;

import com.bvisionry.common.exception.EmailDeliveryException;
import jakarta.activation.DataHandler;
import jakarta.mail.MessagingException;
import jakarta.mail.Part;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.PreencodedMimeBodyPart;
import jakarta.mail.util.ByteArrayDataSource;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.List;

/**
 * SMTP transport via Spring's {@link JavaMailSender}. Used locally with MailHog
 * and for any environment whose network allows outbound SMTP.
 *
 * Activated when {@code bvisionry.mail.transport=smtp} (the default).
 */
@Component
@ConditionalOnProperty(name = "bvisionry.mail.transport", havingValue = "smtp", matchIfMissing = true)
@RequiredArgsConstructor
@Slf4j
public class SmtpMailTransport implements MailTransport {

    private final JavaMailSender mailSender;

    @Value("${bvisionry.mail.from:noreply@bvisionry.com}")
    private String fromAddress;

    @Override
    public void send(String to, String subject, String htmlBody, String replyTo,
                     List<MailAttachment> attachments) {
        boolean multipart = attachments != null && !attachments.isEmpty();
        try {
            MimeMessage message = mailSender.createMimeMessage();
            // multipart=true is required whenever attachments are present so the
            // HTML body and the file parts coexist in a single message.
            MimeMessageHelper helper = new MimeMessageHelper(message, multipart, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            helper.setFrom(fromAddress);
            if (replyTo != null && !replyTo.isBlank()) {
                helper.setReplyTo(replyTo);
            }
            if (multipart) {
                for (MailAttachment att : attachments) {
                    helper.getRootMimeMultipart().addBodyPart(toBase64Part(att));
                }
            }
            mailSender.send(message);
            log.info("Email sent via SMTP to {} ({} attachment(s)) - subject: {}",
                    to, multipart ? attachments.size() : 0, subject);
        } catch (MessagingException | MailException e) {
            log.error("SMTP send to {} failed: {}", to, e.getMessage());
            throw new EmailDeliveryException("Email sending failed. Check mail server configuration and try again.");
        }
    }

    /**
     * Builds an attachment part with the bytes pre-encoded as base64. Using
     * {@link PreencodedMimeBodyPart} pins the Content-Transfer-Encoding to base64
     * so JavaMail never picks a text encoding for the (binary) file — which the
     * SMTP transport would then corrupt by canonicalising bare LFs to CRLF. This
     * keeps the delivered file byte-for-byte identical to the uploaded one.
     */
    private static PreencodedMimeBodyPart toBase64Part(MailAttachment att) throws MessagingException {
        PreencodedMimeBodyPart part = new PreencodedMimeBodyPart("base64");
        byte[] encoded = Base64.getMimeEncoder().encode(att.content());
        part.setDataHandler(new DataHandler(new ByteArrayDataSource(encoded, att.contentType())));
        part.setFileName(att.fileName());
        part.setDisposition(Part.ATTACHMENT);
        return part;
    }
}
