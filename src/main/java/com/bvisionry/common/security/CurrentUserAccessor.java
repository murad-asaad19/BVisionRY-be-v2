package com.bvisionry.common.security;

/**
 * Port for resolving the authenticated principal from feature slices that must
 * not depend on the {@code auth} feature package. Implemented in {@code config}.
 */
public interface CurrentUserAccessor {

    /**
     * @return the authenticated caller
     * @throws org.springframework.security.access.AccessDeniedException if unauthenticated
     */
    CurrentUser require();
}
