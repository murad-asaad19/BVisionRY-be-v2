package com.bvisionry.courseaccess.dto;

import java.util.UUID;

/** An organization the explicit-list picker can choose. */
public record OrgOptionView(UUID orgId, String name) {
}
