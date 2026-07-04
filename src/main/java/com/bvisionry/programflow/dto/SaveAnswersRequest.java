package com.bvisionry.programflow.dto;

import java.util.Map;

import jakarta.validation.constraints.NotNull;

/** Autosave body: the full answers map keyed by field id. */
public record SaveAnswersRequest(@NotNull Map<String, Object> answers) {
}
