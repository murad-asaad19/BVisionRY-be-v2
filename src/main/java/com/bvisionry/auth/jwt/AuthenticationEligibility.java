package com.bvisionry.auth.jwt;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserStatus;

/**
 * The single "may this principal authenticate at all?" decision. Every
 * authentication filter in this package routes through it.
 *
 * <p>It exists because the two filters had drifted: {@link JwtAuthenticationFilter}
 * refused a non-{@link UserStatus#ACTIVE} user, {@link DownloadTokenAuthenticationFilter}
 * did not — so the same suspended account was rejected on the cookie path and accepted
 * on the download-token path. Copying the missing line into the second filter would
 * leave a third one free to drift the same way; one call site cannot.
 *
 * <p><b>Why {@code organizationActive} is a parameter</b> rather than read here from
 * {@code user.getOrganization().isActive()}: {@code Organization} lives in another
 * feature package, and ArchUnit rule 1 ("no class in a feature package should depend on
 * another feature package") is frozen against a committed baseline that pins that call
 * exactly where it already is — inside each filter's {@code doFilterInternal}. Calling
 * {@code isActive()} from here would be a NEW cross-feature violation and fail the
 * build; moving it into {@code common}/{@code config} (the only exempt packages) would
 * launder a real cross-feature dependency into an invisible one and silently shrink the
 * baseline. So the READ stays at the call site and only the DECISION is shared.
 *
 * <p>ponytail: known ceiling — a future filter can still pass a wrong
 * {@code organizationActive}. Structure cannot stop that here, so it is pinned by test
 * instead: every filter has an inactive-organization case. Upgrade path, if the
 * baseline is ever re-frozen: take {@code User} alone and read the organization here.
 */
final class AuthenticationEligibility {

    private AuthenticationEligibility() {
    }

    /**
     * @param user               the principal loaded for this request, never {@code null}
     * @param organizationActive whether {@code user}'s organization is active;
     *                           {@code true} when the user belongs to no organization
     * @return {@code true} when this principal may be authenticated at all. Suspended,
     *         deactivated and still-pending users are refused, so a status change takes
     *         effect on the very next request instead of at token expiry — login,
     *         refresh and SSO already require ACTIVE and this mirrors them.
     */
    static boolean mayAuthenticate(User user, boolean organizationActive) {
        return organizationActive && user.getStatus() == UserStatus.ACTIVE;
    }
}
