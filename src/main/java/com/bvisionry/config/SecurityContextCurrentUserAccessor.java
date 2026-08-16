package com.bvisionry.config;

import org.springframework.security.access.AccessDeniedException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.stereotype.Component;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;

/**
 * {@link CurrentUserAccessor} adapter over the Spring {@link SecurityContextHolder}.
 * Lives in {@code config} (shared wiring layer, allowed to cross feature lines) so
 * feature slices can resolve the caller without importing {@code auth}.
 */
@Component
public class SecurityContextCurrentUserAccessor implements CurrentUserAccessor {

    @Override
    public CurrentUser require() {
        Authentication auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth != null && auth.getPrincipal() instanceof User user) {
            return new CurrentUser(
                    user.getId(),
                    user.getOrganization() != null ? user.getOrganization().getId() : null,
                    user.getName(),
                    user.getRole().name());
        }
        throw new AccessDeniedException("Not authenticated");
    }
}
