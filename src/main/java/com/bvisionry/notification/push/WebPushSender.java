package com.bvisionry.notification.push;

import lombok.extern.slf4j.Slf4j;
import nl.martijndwars.webpush.Encoding;
import nl.martijndwars.webpush.Notification;
import nl.martijndwars.webpush.PushService;
import org.apache.http.HttpResponse;
import org.apache.http.util.EntityUtils;
import org.bouncycastle.jce.provider.BouncyCastleProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.Security;

/**
 * Thin wrapper around the web-push VAPID client. Sends are best-effort:
 * failures are logged and never propagated, and an HTTP 404/410 from the push
 * service means the browser revoked the subscription, so the row is pruned.
 *
 * <p>When the VAPID keys are not configured the sender boots disabled and
 * every send is a silent no-op — the rest of the app must not care.
 */
@Component
@Slf4j
public class WebPushSender {

    private final PushSubscriptionRepository subscriptionRepository;
    private final PushService pushService;

    public WebPushSender(PushSubscriptionRepository subscriptionRepository,
                         @Value("${bvisionry.push.vapid.public-key:}") String publicKey,
                         @Value("${bvisionry.push.vapid.private-key:}") String privateKey,
                         @Value("${bvisionry.push.vapid.subject:mailto:no-reply@bvisionry.com}") String subject)
            throws GeneralSecurityException {
        this.subscriptionRepository = subscriptionRepository;
        if (publicKey.isBlank() || privateKey.isBlank()) {
            this.pushService = null;
            log.warn("VAPID keys not configured (bvisionry.push.vapid.*) — web push is disabled");
        } else {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) == null) {
                Security.addProvider(new BouncyCastleProvider());
            }
            this.pushService = new PushService(publicKey, privateKey, subject);
        }
    }

    public boolean isEnabled() {
        return pushService != null;
    }

    /**
     * Synchronous send of one payload to one browser; callers fan out on the
     * push executor (see {@link PushNotificationService}).
     */
    public void send(PushSubscription subscription, String payloadJson) {
        if (pushService == null) {
            return;
        }
        try {
            // AES128GCM (RFC 8291): the library's legacy AESGCM default sends
            // draft-era Crypto-Key headers that FCM rejects with 403.
            HttpResponse response = pushService.send(new Notification(
                    subscription.getEndpoint(),
                    subscription.getP256dh(),
                    subscription.getAuth(),
                    payloadJson.getBytes(StandardCharsets.UTF_8)), Encoding.AES128GCM);
            int status = response.getStatusLine().getStatusCode();
            if (status == 404 || status == 410) {
                subscriptionRepository.deleteByEndpoint(subscription.getEndpoint());
                log.info("Pruned expired push subscription for user {}", subscription.getUserId());
            } else if (status >= 400) {
                // Push-service rejections carry the reason in the body (e.g.
                // FCM explains VAPID mismatches there) — without it a 403 is
                // undebuggable.
                log.warn("Push send to user {} failed: HTTP {} {}", subscription.getUserId(), status,
                        response.getEntity() != null ? EntityUtils.toString(response.getEntity()) : "");
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            log.warn("Push send to user {} interrupted", subscription.getUserId());
        } catch (Exception e) {
            log.warn("Push send to user {} failed: {}", subscription.getUserId(), e.getMessage());
        }
    }
}
