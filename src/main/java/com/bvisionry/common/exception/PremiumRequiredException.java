package com.bvisionry.common.exception;

import lombok.Getter;

@Getter
public class PremiumRequiredException extends RuntimeException {

    private final String feature;

    public PremiumRequiredException(String feature) {
        super("Premium subscription required for feature: " + feature);
        this.feature = feature;
    }
}
