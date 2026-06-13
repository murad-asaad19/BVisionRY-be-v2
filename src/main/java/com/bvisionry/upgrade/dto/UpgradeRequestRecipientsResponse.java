package com.bvisionry.upgrade.dto;

import java.util.List;

public record UpgradeRequestRecipientsResponse(
        List<String> recipients,
        boolean fallbackToSuperAdmins
) {}
