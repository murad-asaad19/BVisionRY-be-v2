package com.bvisionry.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.web.util.UrlPathHelper;

/**
 * Resolves the request path the way Spring MVC matches it: percent-DECODED,
 * matrix ({@code ;}) content stripped, context path removed.
 *
 * <p>Filters and interceptors that compare {@link HttpServletRequest#getRequestURI()}
 * (raw, still percent-encoded) against the literal paths MVC routes on can be
 * bypassed with a trivially encoded spelling ({@code /%73tart} still reaches the
 * {@code /start} handler, but a raw-URI equality check no longer matches), silently
 * skipping the rate limit or tenancy guard. Every path comparison outside MVC must
 * go through this helper so both sides of the comparison see the same string.
 */
public final class RequestPaths {

    private RequestPaths() {
    }

    /** The decoded, semicolon-stripped path within the application. */
    public static String decoded(HttpServletRequest request) {
        return UrlPathHelper.defaultInstance.getPathWithinApplication(request);
    }
}
