package com.bvisionry.notification.push;

import com.bvisionry.notification.push.dto.NotificationItem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageImpl;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import java.util.List;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The paging read side of the notification inbox. Page and size come straight
 * off a query string, so what is under test is the clamping and the unread
 * branch — not Spring Data, which is mocked.
 */
@ExtendWith(MockitoExtension.class)
class NotificationHistoryServiceTest {

    @Mock
    private UserNotificationRepository repository;

    @InjectMocks
    private NotificationHistoryService service;

    private final UUID userId = UUID.randomUUID();

    private ArgumentCaptor<Pageable> servingAll() {
        ArgumentCaptor<Pageable> captor = ArgumentCaptor.forClass(Pageable.class);
        when(repository.findByUserId(eq(userId), captor.capture())).thenReturn(Page.empty());
        return captor;
    }

    @Test
    void ordersNewestFirstWithoutTrustingTheCaller() {
        ArgumentCaptor<Pageable> captor = servingAll();

        service.page(userId, 2, 20, false);

        Pageable used = captor.getValue();
        assertThat(used.getPageNumber()).isEqualTo(2);
        assertThat(used.getPageSize()).isEqualTo(20);
        assertThat(used.getSort().getOrderFor("createdAt"))
                .isNotNull()
                .extracting(Sort.Order::getDirection)
                .isEqualTo(Sort.Direction.DESC);
    }

    @Test
    void clampsAHostilePageAndSize() {
        ArgumentCaptor<Pageable> captor = servingAll();

        // A negative page is an IllegalArgumentException from PageRequest, and
        // an unbounded size is a table scan per request.
        service.page(userId, -5, 10_000, false);

        assertThat(captor.getValue().getPageNumber()).isZero();
        assertThat(captor.getValue().getPageSize()).isEqualTo(100);
    }

    @Test
    void aSizeOfZeroDoesNotBecomeAnEmptyPageForever() {
        ArgumentCaptor<Pageable> captor = servingAll();

        service.page(userId, 0, 0, false);

        assertThat(captor.getValue().getPageSize()).isEqualTo(1);
    }

    @Test
    void unreadOnlyUsesTheFilteredQueryAndNeverTheFullOne() {
        when(repository.findByUserIdAndReadAtIsNull(eq(userId), any())).thenReturn(Page.empty());

        service.page(userId, 0, 20, true);

        verify(repository).findByUserIdAndReadAtIsNull(eq(userId), any());
        verify(repository, never()).findByUserId(any(), any());
    }

    @Test
    void mapsRowsToItemsWithoutLosingThePageMetadata() {
        UserNotification row = new UserNotification();
        row.setId(UUID.randomUUID());
        row.setUserId(userId);
        row.setType(NotificationType.RESULTS_READY);
        row.setTitle("Your results are ready");
        row.setBody("Your \"FRI\" results are available.");
        row.setUrl("/app/assessments/x/results");
        when(repository.findByUserId(eq(userId), any()))
                .thenReturn(new PageImpl<>(List.of(row), Pageable.ofSize(20), 137));

        Page<NotificationItem> page = service.page(userId, 0, 20, false);

        // totalElements is the whole point of paging here: it is what tells the
        // inbox there is anything past page 1.
        assertThat(page.getTotalElements()).isEqualTo(137);
        assertThat(page.getContent()).singleElement()
                .satisfies(item -> {
                    assertThat(item.id()).isEqualTo(row.getId());
                    assertThat(item.type()).isEqualTo("RESULTS_READY");
                    assertThat(item.url()).isEqualTo("/app/assessments/x/results");
                });
    }
}
