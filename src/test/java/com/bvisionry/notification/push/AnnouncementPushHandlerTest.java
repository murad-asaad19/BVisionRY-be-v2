package com.bvisionry.notification.push;

import com.bvisionry.common.event.CommunicationEvents;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentMatchers;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.List;
import java.util.UUID;

import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * The announcement fan-out goes through the ORDINARY dispatch — one batched
 * {@code notifyUsers} typed {@code ANNOUNCEMENT} — so opt-outs and history are
 * handled by the code that already handles them for every other type (see
 * {@link PushNotificationServiceTest}), not re-implemented here.
 */
@ExtendWith(MockitoExtension.class)
class AnnouncementPushHandlerTest {

    @Mock private PushNotificationService pushNotificationService;
    @InjectMocks private AnnouncementPushHandler handler;

    private final UUID announcementId = UUID.randomUUID();
    private final UUID first = UUID.randomUUID();
    private final UUID second = UUID.randomUUID();

    @Test
    void deliversTheCohortInOneBatchWithADeepLinkToThePost() {
        handler.onAnnouncementPosted(new CommunicationEvents.AnnouncementPosted(
                announcementId, UUID.randomUUID(), "Spring Cohort", "Ada",
                "Demo day is Friday.", List.of(first, second)));

        // ONE call for the whole cohort — not one per recipient.
        verify(pushNotificationService).notifyUsers(List.of(first, second),
                NotificationType.ANNOUNCEMENT, "Announcement · Spring Cohort",
                "Ada: Demo day is Friday.",
                "/app/program?announcement=" + announcementId);
        verify(pushNotificationService, never()).notifyUser(
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any(),
                ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void anEmptyCohortStillTakesTheBatchPath() {
        // dispatch() itself short-circuits on an empty audience, so there is no
        // reason for the handler to grow a branch of its own.
        handler.onAnnouncementPosted(new CommunicationEvents.AnnouncementPosted(
                announcementId, UUID.randomUUID(), "Empty Cohort", "Ada",
                "Anyone there?", List.of()));

        verify(pushNotificationService).notifyUsers(
                ArgumentMatchers.eq(List.of()),
                ArgumentMatchers.eq(NotificationType.ANNOUNCEMENT),
                ArgumentMatchers.any(), ArgumentMatchers.any(), ArgumentMatchers.any());
    }

    @Test
    void aTitleIsBoundedToTheNotificationTitleColumn() {
        handler.onAnnouncementPosted(new CommunicationEvents.AnnouncementPosted(
                announcementId, UUID.randomUUID(), "N".repeat(400), null,
                "Body.", List.of(first)));

        verify(pushNotificationService).notifyUsers(
                ArgumentMatchers.eq(List.of(first)),
                ArgumentMatchers.eq(NotificationType.ANNOUNCEMENT),
                ArgumentMatchers.argThat(title -> title.length() == 200),
                ArgumentMatchers.eq("Body."),
                ArgumentMatchers.any());
    }
}
