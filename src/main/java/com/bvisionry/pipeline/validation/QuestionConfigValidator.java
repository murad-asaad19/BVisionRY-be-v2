package com.bvisionry.pipeline.validation;

import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

@Component
public class QuestionConfigValidator {

    @SuppressWarnings("unchecked")
    public void validate(QuestionType type, Map<String, Object> configJson) {
        if (configJson == null) return;
        switch (type) {
            case FREE_TEXT -> validateFreeText(configJson);
            case LIKERT -> validateLikert(configJson);
            case MULTIPLE_CHOICE -> validateMultipleChoice(configJson);
            case MULTI_INPUT -> validateMultiInput(configJson);
        }
    }

    private void validateFreeText(Map<String, Object> config) {
        Integer minChars = getInt(config, "minChars");
        Integer maxChars = getInt(config, "maxChars");
        if (minChars != null && maxChars != null && minChars > maxChars) {
            throw new BadRequestException("minChars cannot be greater than maxChars");
        }
        if (minChars != null && minChars < 0) {
            throw new BadRequestException("minChars must be non-negative");
        }
    }

    @SuppressWarnings("unchecked")
    private void validateLikert(Map<String, Object> config) {
        Object labels = config.get("labels");
        if (labels == null) {
            throw new BadRequestException("Likert questions require a 'labels' map");
        }
        if (labels instanceof Map<?, ?> labelsMap && labelsMap.size() < 2) {
            throw new BadRequestException("Likert scale must have at least 2 labels");
        }
    }

    private void validateMultipleChoice(Map<String, Object> config) {
        Object options = config.get("options");
        if (options == null) {
            throw new BadRequestException("Multiple choice questions require an 'options' list");
        }
        if (options instanceof List<?> optionsList) {
            if (optionsList.size() < 2) {
                throw new BadRequestException("Multiple choice must have at least 2 options");
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void validateMultiInput(Map<String, Object> config) {
        Object columns = config.get("columns");
        if (columns == null) {
            throw new BadRequestException("Multi Input questions require a 'columns' list");
        }
        if (!(columns instanceof List<?> colList)) {
            throw new BadRequestException("Multi Input 'columns' must be a list");
        }
        if (colList.isEmpty()) {
            throw new BadRequestException("Multi Input must have at least 1 column");
        }
        Object columnTypes = config.get("columnTypes");
        if (columnTypes != null) {
            if (!(columnTypes instanceof List<?> typeList)) {
                throw new BadRequestException("Multi Input 'columnTypes' must be a list");
            }
            if (typeList.size() != colList.size()) {
                throw new BadRequestException("Multi Input 'columnTypes' must match the number of columns");
            }
            for (Object t : typeList) {
                if (!(t instanceof String s) || (!"TEXT".equals(s) && !"NUMBER".equals(s))) {
                    throw new BadRequestException("Multi Input column types must be 'TEXT' or 'NUMBER'");
                }
            }
        }
        Object rows = config.get("rows");
        if (rows == null) {
            throw new BadRequestException("Multi Input questions require a 'rows' list");
        }
        if (rows instanceof List<?> rowList && rowList.isEmpty()) {
            throw new BadRequestException("Multi Input must have at least 1 row");
        }
    }

    private Integer getInt(Map<String, Object> config, String key) {
        Object value = config.get(key);
        if (value == null) return null;
        if (value instanceof Number n) return n.intValue();
        return null;
    }
}
