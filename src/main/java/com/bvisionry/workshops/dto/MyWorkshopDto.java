package com.bvisionry.workshops.dto;

import java.util.UUID;

import com.bvisionry.workshops.domain.WorkshopStatus;

/** One entry of the learner's "My Workshops" list. */
public record MyWorkshopDto(
        UUID id,
        String name,
        WorkshopStatus status,
        String teamName,
        boolean lead) {
}
