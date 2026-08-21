package com.bvisionry.common.exception;

/**
 * The credentials were CORRECT but the account is unusable — the user is not
 * ACTIVE, or their organization is suspended. A subclass of
 * {@link AuthenticationException} so it still maps to the same 401 with its own
 * message, but the login backoff (which exists to cap password GUESSING) must NOT
 * advance for it: the password was right, so penalizing it would lock a
 * legitimate user out with their own correct credentials once the account or org
 * is restored.
 */
public class AccountNotActiveException extends AuthenticationException {

    public AccountNotActiveException(String message) {
        super(message);
    }
}
