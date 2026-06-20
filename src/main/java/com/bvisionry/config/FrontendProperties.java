package com.bvisionry.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Binds {@code bvisionry.frontend.*} keys from {@code application.properties}.
 *
 * <p>The single source of truth for the public web origin (the Next.js app).
 * Previously every service that needed to build a user-facing link re-declared
 * its own {@code @Value("${bvisionry.frontend.base-url:...}")} field, which let
 * the default drift (some beans defaulted to the stale Vite {@code :5173} port)
 * and meant the trailing-slash normalization lived in only one of a dozen call
 * sites. Centralizing the binding here — and the URL building in
 * {@link FrontendUrls} — keeps every generated link consistent.
 */
@ConfigurationProperties(prefix = "bvisionry.frontend")
public class FrontendProperties {

    /**
     * Public origin of the web app, e.g. {@code https://bvisionry.com} in prod.
     * Defaults to the Next.js dev port; {@code application.properties} resolves
     * this to {@code http://localhost:3000} and the prod/e2e profiles override it.
     */
    private String baseUrl = "http://localhost:3000";

    public String getBaseUrl() { return baseUrl; }
    public void setBaseUrl(String baseUrl) { this.baseUrl = baseUrl; }
}
