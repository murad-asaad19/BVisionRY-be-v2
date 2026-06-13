package com.bvisionry.survey.service;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.survey.entity.SurveyQuestionType;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

@Component
public class SurveyQuestionConfigValidator {

    public void validate(SurveyQuestionType type, Map<String, Object> config) {
        switch (type) {
            case SHORT_TEXT -> validateShortText(config);
            case MULTIPLE_CHOICE -> validateMultipleChoice(config);
            case LIKERT -> validateLikert(config);
            case NUMBER -> validateNumber(config);
            case SELF_RATING -> validateSelfRating(config);
        }
    }

    private void validateShortText(Map<String, Object> config) {
        if (config == null) return;
        Integer min = asOptionalNonNegative(config.get("minLength"), "minLength");
        Integer max = asOptionalNonNegative(config.get("maxLength"), "maxLength");
        if (min != null && max != null && max < min) {
            throw new BadRequestException("configJson.maxLength must be >= configJson.minLength");
        }
    }

    private void validateMultipleChoice(Map<String, Object> config) {
        if (config == null || !(config.get("options") instanceof List<?> rawOptions) || rawOptions.isEmpty()) {
            throw new BadRequestException("configJson.options must be a non-empty list of strings");
        }
        List<String> options = new ArrayList<>();
        Set<String> seen = new HashSet<>();
        for (Object raw : rawOptions) {
            if (!(raw instanceof String s) || s.isBlank()) {
                throw new BadRequestException("configJson.options entries must be non-empty strings");
            }
            if (!seen.add(s)) {
                throw new BadRequestException("configJson.options entries must be unique");
            }
            options.add(s);
        }
        if (options.size() < 2) {
            throw new BadRequestException("Multiple choice questions need at least 2 options");
        }
        Object multiSelect = config.get("multiSelect");
        if (multiSelect != null && !(multiSelect instanceof Boolean)) {
            throw new BadRequestException("configJson.multiSelect must be a boolean");
        }
    }

    private void validateLikert(Map<String, Object> config) {
        if (config == null || !(config.get("labels") instanceof List<?> rawLabels)) {
            throw new BadRequestException("configJson.labels must be a list of 5 strings for LIKERT");
        }
        if (rawLabels.size() != 5) {
            throw new BadRequestException("LIKERT questions require exactly 5 labels");
        }
        for (Object raw : rawLabels) {
            if (!(raw instanceof String s) || s.isBlank()) {
                throw new BadRequestException("configJson.labels entries must be non-empty strings");
            }
        }
    }

    private void validateNumber(Map<String, Object> config) {
        // NUMBER accepts empty or null config for v1 — nothing to validate.
    }

    private void validateSelfRating(Map<String, Object> config) {
        if (config == null) return;
        Number min = asOptionalNumber(config.get("min"), "min");
        Number max = asOptionalNumber(config.get("max"), "max");
        Number step = asOptionalNumber(config.get("step"), "step");
        if (min != null && max != null && min.doubleValue() >= max.doubleValue()) {
            throw new BadRequestException("configJson.min must be less than configJson.max");
        }
        if (step != null && step.doubleValue() <= 0) {
            throw new BadRequestException("configJson.step must be positive");
        }
        Object label = config.get("label");
        if (label != null && !(label instanceof String)) {
            throw new BadRequestException("configJson.label must be a string");
        }
    }

    private Number asOptionalNumber(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof Number n)) {
            throw new BadRequestException("configJson." + field + " must be a number");
        }
        return n;
    }

    private Integer asOptionalNonNegative(Object raw, String field) {
        if (raw == null) return null;
        if (!(raw instanceof Number n)) {
            throw new BadRequestException("configJson." + field + " must be a number");
        }
        int val = n.intValue();
        if (val < 0) {
            throw new BadRequestException("configJson." + field + " must be non-negative");
        }
        return val;
    }
}
