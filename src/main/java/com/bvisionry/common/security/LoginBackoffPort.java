package com.bvisionry.common.security;

/**
 * Shared-kernel view of "this account's password-login failure history may be
 * forgotten", consumed by feature packages that legitimately restore an
 * account's ability to sign in but must not depend on the {@code aiconfig}
 * package (where the limiter lives) directly.
 *
 * <p>Exactly the seam {@link com.bvisionry.common.media.MediaQuotaPort} is:
 * features may depend on {@code common}, never the reverse, and
 * {@code ArchitectureRulesTest} rule 1 freezes every existing feature→feature
 * edge, so a NEW one (here: {@code auth} → {@code aiconfig}) fails the build.
 *
 * <p>Implemented by {@code RateLimitService} in {@code aiconfig.service}.
 */
public interface LoginBackoffPort {

    /**
     * Forget every recorded password-login failure for this address, cancelling
     * any refusal currently being served.
     *
     * <p>Only ever legitimate after the caller has PROVEN control of the
     * account by some means other than the password being throttled — a
     * successful sign-in, or a password reset whose single-use emailed token
     * proves mailbox ownership. Without that, the backoff would be a permanent
     * lockout for the victim of a guessing attack: they cannot sign in (wrong
     * password unknown to them, and refused anyway) and resetting the password
     * would leave the refusal armed against the NEW password too.
     *
     * @param emailKey the account's email, lowercased and trimmed — the same
     *                 normalisation the login path keys the counter on
     */
    void clearLoginFailures(String emailKey);
}
