package com.bvisionry.leadmagnet;

import com.bvisionry.media.MediaService;
import com.bvisionry.notification.EmailService;
import com.bvisionry.platform.LeadMagnetSettingsService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Component;

/**
 * Off-thread delivery of the lead-magnet PDF. Runs on the shared
 * {@code emailExecutor} so neither the (potentially multi-MB) asset fetch nor
 * the SMTP/Resend send ever touches the public, unauthenticated request thread —
 * the controller returns as soon as the lead row is persisted.
 *
 * <p>This lives in its own bean (rather than as an {@code @Async} method on
 * {@link LeadMagnetService}) so the call crosses the Spring proxy boundary: a
 * self-invoked {@code @Async} method would silently run synchronously.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class LeadMagnetDispatcher {

    private final LeadMagnetSettingsService settingsService;
    private final MediaService mediaService;
    private final EmailService emailService;

    /**
     * Loads the configured PDF and emails it to {@code email}. Best-effort: the
     * lead is already persisted, so a failure here is logged, never propagated.
     *
     * <p>A configured-but-unreadable marker (deleted object, wrong bucket,
     * unreachable external URL) is logged at ERROR with the marker and stack
     * trace, so a broken delivery config is visible to operators instead of
     * silently dropping every visitor's PDF.
     */
    @Async("emailExecutor")
    public void dispatchPdf(String email) {
        String marker = settingsService.currentMarker();
        if (marker == null) {
            log.warn("Lead-magnet PDF not configured — captured {} but sent no email. "
                    + "Upload a PDF in Platform Settings to enable delivery.", email);
            return;
        }
        try {
            byte[] pdf = mediaService.fetchBytes(marker);
            String fileName = settingsService.deriveFileName(marker);
            emailService.sendLeadMagnet(email, pdf, fileName);
        } catch (Exception e) {
            log.error("Lead-magnet PDF delivery to {} failed (lead saved, no email sent); marker={}",
                    email, marker, e);
        }
    }
}
