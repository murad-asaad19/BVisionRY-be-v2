package com.bvisionry.workshops.dto;

import java.util.Map;

import com.bvisionry.workshops.domain.WorkshopTaskAssignee;
import com.bvisionry.workshops.domain.WorkshopTaskType;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record CreateTaskRequest(
        @NotNull WorkshopTaskType type,
        @NotNull WorkshopTaskAssignee assignee,
        @NotBlank @Size(max = 200) String title,
        Map<String, Object> config) {
}
