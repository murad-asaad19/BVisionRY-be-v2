package com.bvisionry.contact.dto;

import java.util.List;

public record ContactRecipientsResponse(
        List<String> recipients,
        boolean fallbackToSuperAdmins
) {}
