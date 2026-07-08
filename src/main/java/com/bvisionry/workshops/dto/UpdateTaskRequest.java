package com.bvisionry.workshops.dto;

import java.util.Map;

import com.bvisionry.workshops.domain.WorkshopTaskAssignee;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** Full-replace task update; the type is immutable after creation. */
public record UpdateTaskRequest(
        @NotNull WorkshopTaskAssignee assignee,
        @NotBlank @Size(max = 200) String title,
        Map<String, Object> config) {
}
