package com.bvisionry.lead.dto;

import java.util.List;

public record LeadRecipientsResponse(
        List<String> recipients,
        boolean fallbackToSuperAdmins
) {}
