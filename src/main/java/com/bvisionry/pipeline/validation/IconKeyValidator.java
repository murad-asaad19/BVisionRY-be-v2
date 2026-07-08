package com.bvisionry.pipeline.validation;

import com.bvisionry.common.exception.BadRequestException;
import org.springframework.stereotype.Component;

import java.util.Set;

@Component
public class IconKeyValidator {

    /** Must match the ICONS map in bvisionry-frontend/src/shared/components/data-display/PillarIcon.tsx */
    private static final Set<String> VALID_ICON_KEYS = Set.of(
            "compass", "shield", "ear", "rocket", "eye", "search",
            "globe", "bolt", "fire", "cog", "mountain",
            "target", "star", "brain", "heart", "users", "lightbulb"
    );

    public void validate(String iconKey) {
        if (iconKey == null || iconKey.isBlank()) return;
        if (!VALID_ICON_KEYS.contains(iconKey)) {
            throw new BadRequestException("Invalid icon key: '" + iconKey + "'. Valid keys: " + VALID_ICON_KEYS);
        }
    }
}
