package com.bvisionry.notification.push.dto;

/** One toggle row in the notification-preferences UI, already role-filtered. */
public record PreferenceItem(String type, String label, String description, boolean enabled) {
}
