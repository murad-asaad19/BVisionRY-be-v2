package com.bvisionry.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.Arrays;

/**
 * Resolves the real client IP behind a known, fixed number of trusted reverse
 * proxies (B5 / F11).
 *
 * <p>Each proxy in the chain APPENDS the address it received the request from to
 * {@code X-Forwarded-For}, so the address the trusted edge actually observed is the
 * entry {@code trusted-proxy-hops} positions from the RIGHT. We deliberately count
 * from the right, never the left: any XFF entries a malicious client injects sit to
 * the left of the edge's appended value and therefore can never reach the resolved
 * index — so the client cannot spoof its IP to rotate (bypass) or collapse
 * (self-DoS) the per-IP rate-limit buckets. Taking the leftmost entry, as this class
 * used to, is exactly the spoofable behavior that defeated rate limiting.
 *
 * <p>Configure {@code bvisionry.security.trusted-proxy-hops} to the number of
 * trusted proxies in front of the app: {@code 0} for local/direct (use the raw TCP
 * peer), {@code 1} for a single edge (e.g. Railway). This requires that Spring's own
 * forwarded-header processing is NOT rewriting/stripping the header
 * ({@code server.forward-headers-strategy=none}), so we parse the raw chain here.
 */
@Component
public class ClientIpResolver {

    private final int trustedProxyHops;

    public ClientIpResolver(
            @Value("${bvisionry.security.trusted-proxy-hops:0}") int trustedProxyHops) {
        this.trustedProxyHops = Math.max(0, trustedProxyHops);
    }

    /**
     * Returns the real client IP: the X-Forwarded-For entry {@code trustedProxyHops}
     * positions from the right when behind trusted proxies, otherwise the raw
     * {@link HttpServletRequest#getRemoteAddr()} (direct connection / no chain).
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (trustedProxyHops <= 0) {
            // Not behind a trusted proxy (e.g. local dev) — the TCP peer IS the client.
            return remoteAddr;
        }

        String xForwarded = request.getHeader("X-Forwarded-For");
        if (xForwarded == null || xForwarded.isBlank()) {
            // No forwarding chain present — direct hit (e.g. an internal health probe).
            return remoteAddr;
        }

        String[] entries = Arrays.stream(xForwarded.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toArray(String[]::new);
        if (entries.length == 0) {
            return remoteAddr;
        }

        // The edge's appended (trustworthy) entry is `trustedProxyHops` from the right.
        // If the chain is shorter than configured (misconfig / fewer hops than
        // expected), fall back to the leftmost present entry rather than indexing
        // out of bounds.
        int index = entries.length - trustedProxyHops;
        if (index < 0) {
            index = 0;
        }
        return entries[index];
    }
}
