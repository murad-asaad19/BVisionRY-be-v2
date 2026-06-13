package com.bvisionry.notification.transport;

import com.bvisionry.common.exception.EmailDeliveryException;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.mail.MailException;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

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
    public void send(String to, String subject, String htmlBody) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, true, "UTF-8");
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(htmlBody, true);
            helper.setFrom(fromAddress);
            mailSender.send(message);
            log.info("Email sent via SMTP to {} - subject: {}", to, subject);
        } catch (MessagingException | MailException e) {
            log.error("SMTP send to {} failed: {}", to, e.getMessage());
            throw new EmailDeliveryException("Email sending failed. Check mail server configuration and try again.");
        }
    }
}
