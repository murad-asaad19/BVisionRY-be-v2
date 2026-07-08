package com.bvisionry.notification.push.dto;

import java.util.List;

public record NotificationsResponse(List<NotificationItem> notifications) {
}
