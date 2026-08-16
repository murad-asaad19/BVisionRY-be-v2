package com.bvisionry.common.security;

/**
 * Shared-kernel view of one account's password-login failure history — read it,
 * add to it, forget it — consumed by feature packages that legitimately drive
 * the per-address guess backoff but must not depend on the {@code aiconfig}
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
     * Refuse this address outright while a refusal it earned is still live, and
     * do nothing otherwise. Read-only: it never counts the attempt, which is
     * {@link #recordLoginFailure}'s job alone.
     *
     * <p>Only ever legitimate AFTER the credential check has already REJECTED
     * the attempt. The backoff caps the rate of wrong guesses against one
     * address and must never refuse a proven-correct password: consulted up
     * front it would let anyone who merely knows the address keep a block armed
     * against the owner, which is the hard lockout this whole design exists to
     * avoid.
     *
     * @param emailKey the submitted email, lowercased and trimmed — keyed
     *                 whether or not an account exists, so a throttled
     *                 nonexistent address is indistinguishable from a real one
     */
    void checkLoginBackoff(String emailKey);

    /**
     * Count one REJECTED password login against this address and arm the next
     * refusal.
     *
     * <p>Legitimate only on a credential that has actually failed. Calling it
     * for anything else — an inactive account, an expired org, any refusal the
     * owner cannot fix by typing their password correctly — walks the ladder up
     * on someone whose credentials were right, and locks them out of their own
     * account the moment the unrelated block is lifted.
     *
     * @param emailKey the submitted email, lowercased and trimmed — the same
     *                 normalisation {@link #checkLoginBackoff} reads
     * @return the seconds this failure armed a refusal for, or {@code 0} when it
     *         armed nothing (still inside the free budget, or a refusal was
     *         already live and this attempt must not advance the ladder twice)
     */
    int recordLoginFailure(String emailKey);

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
