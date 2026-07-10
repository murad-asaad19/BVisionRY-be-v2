package com.bvisionry.common.exception;

/**
 * A rejection inside the SSO sign-in / membership-binding flow that carries a stable,
 * URL-safe error code for the frontend's {@code /login?error=<code>} surface.
 *
 * <p>Extends {@link BadRequestException} so the REST paths that share these guards
 * (e.g. invitation acceptance with registration) keep answering 400 with the human
 * message, while {@code OAuth2Controller} can redirect with the precise code instead
 * of collapsing every rejection into {@code join_invalid}/{@code invitation_invalid}.
 */
public class SsoFlowException extends BadRequestException {

    private final String errorCode;

    public SsoFlowException(String errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public String getErrorCode() {
        return errorCode;
    }
}
