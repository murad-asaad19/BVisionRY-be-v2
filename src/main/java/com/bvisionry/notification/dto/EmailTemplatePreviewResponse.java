package com.bvisionry.notification.dto;

public record EmailTemplatePreviewResponse(
        String subject,
        String htmlBody,
        String error
) {
    public static EmailTemplatePreviewResponse ok(String subject, String htmlBody) {
        return new EmailTemplatePreviewResponse(subject, htmlBody, null);
    }

    public static EmailTemplatePreviewResponse error(String message) {
        return new EmailTemplatePreviewResponse(null, null, message);
    }
}
