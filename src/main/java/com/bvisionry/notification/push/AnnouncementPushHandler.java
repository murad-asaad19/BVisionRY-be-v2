package com.bvisionry.notification.push;

import org.springframework.stereotype.Component;
import org.springframework.transaction.event.TransactionPhase;
import org.springframework.transaction.event.TransactionalEventListener;

import com.bvisionry.common.event.CommunicationEvents;
import com.bvisionry.common.util.TextTruncator;

import lombok.RequiredArgsConstructor;

/**
 * Delivers cohort announcements through the ordinary notification pipeline —
 * the SAME {@link PushNotificationService#notifyUser} every other type uses, so
 * per-user opt-outs ({@link NotificationOptOut}) and in-app history are honored
 * without announcements owning any of that logic.
 *
 * <p>AFTER_COMMIT like {@link ProgramFlowPushHandler}: a rolled-back post must
 * not notify a cohort.
 */
@Component
@RequiredArgsConstructor
public class AnnouncementPushHandler {

    /** The recipient's cohort surface — where COHORT_ENROLLED already sends them. */
    private static final String JOURNEY_URL = "/app/program";

    /** {@code notifications.title} is VARCHAR(200); a cohort name is user-supplied. */
    private static final int TITLE_MAX = 200;

    private final PushNotificationService pushNotificationService;

    @TransactionalEventListener(phase = TransactionPhase.AFTER_COMMIT)
    public void onAnnouncementPosted(CommunicationEvents.AnnouncementPosted event) {
        String title = TextTruncator.truncate(
                "Announcement · " + event.cohortName(), TITLE_MAX, null);
        // The body IS the announcement: recipients read it in the bell without
        // needing a page that does not exist for them.
        String body = event.authorName() == null
                ? event.body()
                : event.authorName() + ": " + event.body();
        // Batch, not a loop: a cohort is the one audience big enough for the
        // per-recipient cost to matter (200 members = 3 statements, not ~600).
        // The deep link carries the announcement id so the bell can offer
        // "Report" on the post the recipient is actually reading.
        pushNotificationService.notifyUsers(event.recipientIds(), NotificationType.ANNOUNCEMENT,
                title, body, JOURNEY_URL + "?announcement=" + event.announcementId());
    }
}
