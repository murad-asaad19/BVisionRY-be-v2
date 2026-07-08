package com.bvisionry.config;

import org.springframework.stereotype.Component;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;

/**
 * {@link CurrentUserAccessor} adapter. Lives in {@code config} (shared wiring
 * layer, allowed to cross feature lines) so feature slices like
 * {@code programflow} can resolve the caller without importing {@code auth}.
 */
@Component
public class SecurityContextCurrentUserAccessor implements CurrentUserAccessor {

    @Override
    public CurrentUser require() {
        User user = SecurityUtils.getCurrentUser();
        return new CurrentUser(
                user.getId(),
                user.getOrganization() != null ? user.getOrganization().getId() : null,
                user.getName(),
                user.getRole().name());
    }
}
