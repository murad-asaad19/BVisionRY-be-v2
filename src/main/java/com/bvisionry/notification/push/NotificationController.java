package com.bvisionry.notification.push;

import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.notification.push.dto.NotificationItem;
import com.bvisionry.notification.push.dto.PreferencesResponse;
import com.bvisionry.notification.push.dto.SubscribeRequest;
import com.bvisionry.notification.push.dto.UnreadCountResponse;
import com.bvisionry.notification.push.dto.UpdatePreferenceRequest;
import com.bvisionry.notification.push.dto.VapidPublicKeyResponse;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.UUID;

/**
 * Current-user notification endpoints: the VAPID public key for the browser's
 * subscribe call, push-subscription registration, and per-type preferences.
 * Every route is self-scoped to the authenticated user — no admin surface.
 */
@RestController
@RequestMapping("/api/notifications")
@PreAuthorize("isAuthenticated()")
public class NotificationController {

    private final NotificationSettingsService settingsService;
    private final NotificationHistoryService historyService;
    /** The feature-neutral "who is calling" port ({@code common}, exempt from the ArchUnit ratchet). */
    private final CurrentUserAccessor currentUser;
    private final String vapidPublicKey;

    public NotificationController(NotificationSettingsService settingsService,
                                  NotificationHistoryService historyService,
                                  CurrentUserAccessor currentUser,
                                  @Value("${bvisionry.push.vapid.public-key:}") String vapidPublicKey) {
        this.settingsService = settingsService;
        this.historyService = historyService;
        this.currentUser = currentUser;
        this.vapidPublicKey = vapidPublicKey;
    }

    /**
     * The caller's history, newest first — ONE endpoint for both readers of it.
     * The bell asks for {@code ?size=20&page=0} and shows the first page; the
     * full inbox at {@code /app/notifications} walks the pages and can narrow to
     * {@code ?unread=true}. It replaces a {@code ?limit=} list capped at 100 with
     * no way to reach anything older (roadmap §10 friction #8).
     */
    @GetMapping
    public ResponseEntity<Page<NotificationItem>> list(@RequestParam(defaultValue = "0") int page,
                                                      @RequestParam(defaultValue = "20") int size,
                                                      @RequestParam(defaultValue = "false") boolean unread) {
        return ResponseEntity.ok(
                historyService.page(currentUser.require().userId(), page, size, unread));
    }

    @GetMapping("/unread-count")
    public ResponseEntity<UnreadCountResponse> unreadCount() {
        return ResponseEntity.ok(new UnreadCountResponse(
                historyService.unreadCount(currentUser.require().userId())));
    }

    @PostMapping("/{id}/read")
    public ResponseEntity<Void> markRead(@PathVariable UUID id) {
        historyService.markRead(currentUser.require().userId(), id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/read-all")
    public ResponseEntity<Void> markAllRead() {
        historyService.markAllRead(currentUser.require().userId());
        return ResponseEntity.noContent().build();
    }

    /** Empty publicKey means push is not configured; the client hides the enable button. */
    @GetMapping("/public-key")
    public ResponseEntity<VapidPublicKeyResponse> publicKey() {
        return ResponseEntity.ok(new VapidPublicKeyResponse(vapidPublicKey));
    }

    @PostMapping("/subscriptions")
    public ResponseEntity<Void> subscribe(@Valid @RequestBody SubscribeRequest request) {
        settingsService.subscribe(currentUser.require().userId(),
                request.endpoint(), request.p256dh(), request.auth());
        return ResponseEntity.noContent().build();
    }

    @DeleteMapping("/subscriptions")
    public ResponseEntity<Void> unsubscribe(@RequestParam String endpoint) {
        settingsService.unsubscribe(currentUser.require().userId(), endpoint);
        return ResponseEntity.noContent().build();
    }

    @GetMapping("/preferences")
    public ResponseEntity<PreferencesResponse> preferences() {
        CurrentUser me = currentUser.require();
        return ResponseEntity.ok(new PreferencesResponse(
                settingsService.preferencesFor(me.userId(), UserRole.valueOf(me.role()))));
    }

    @PutMapping("/preferences/{type}")
    public ResponseEntity<Void> updatePreference(@PathVariable NotificationType type,
                                                 @Valid @RequestBody UpdatePreferenceRequest request) {
        CurrentUser me = currentUser.require();
        settingsService.setPreference(me.userId(), UserRole.valueOf(me.role()), type, request.enabled());
        return ResponseEntity.noContent().build();
    }
}
