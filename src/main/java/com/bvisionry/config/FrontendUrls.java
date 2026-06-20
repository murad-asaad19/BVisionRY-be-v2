package com.bvisionry.config;

import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.stereotype.Component;

/**
 * Builds absolute, user-facing links onto the public web app.
 *
 * <p>The frontend origin is normalized once at construction (any trailing
 * slashes are stripped), so every link this helper produces joins the base and
 * the path with exactly one separator — a configured base-url ending in
 * {@code /} can never yield a malformed {@code https://app//a/<token>} link.
 *
 * <p>Inject this instead of re-declaring a {@code frontendBaseUrl} field: it is
 * the single place that knows the origin and how to glue a relative path onto it.
 */
@Component
@EnableConfigurationProperties(FrontendProperties.class)
public class FrontendUrls {

    private final String base;

    public FrontendUrls(FrontendProperties properties) {
        String configured = properties.getBaseUrl();
        this.base = configured == null ? "" : configured.replaceAll("/+$", "");
    }

    /** Normalized origin with no trailing slash, e.g. {@code https://bvisionry.com}. */
    public String base() {
        return base;
    }

    /**
     * Joins {@code relativePath} onto the origin, guaranteeing exactly one leading
     * slash on the path. Accepts paths that already start with {@code /} (e.g.
     * {@code survey.url()}) and query strings (e.g. {@code /login?error=x}).
     */
    public String path(String relativePath) {
        if (relativePath == null || relativePath.isEmpty()) {
            return base;
        }
        return base + (relativePath.startsWith("/") ? relativePath : "/" + relativePath);
    }

    /** Public assessment link for a share token: {@code <base>/a/<token>}. */
    public String assessmentLink(Object token) {
        return path("/a/" + token);
    }
}
