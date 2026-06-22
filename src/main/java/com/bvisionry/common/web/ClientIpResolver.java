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
 *
 * <p><b>Authoritative client-IP via the Vercel BFF.</b> Public/QR traffic does not
 * reach the backend browser-direct; it goes through the server-side Vercel BFF, so
 * the backend's TCP peer is Vercel and the raw XFF chain no longer ends at the real
 * respondent. To attribute per-IP rate-limit buckets to the real respondent, the BFF
 * sends the real client IP in {@code X-Bvisionry-Client-Ip} and authenticates itself
 * with {@code X-Bvisionry-Proxy-Secret}. We trust {@code X-Bvisionry-Client-Ip} ONLY
 * when {@code X-Bvisionry-Proxy-Secret} matches the configured backend secret
 * ({@code bvisionry.proxy.shared-secret}, env {@code BVISIONRY_PROXY_SHARED_SECRET}).
 * If the secret is unset (local dev) or does not match, the header is ignored and we
 * fall back to the right-counted XFF / remoteAddr logic — a client can never spoof
 * its IP without also possessing the shared secret. This resolver is the single
 * choke point for client-IP resolution; all rate-limit buckets key off it.
 */
@Component
public class ClientIpResolver {

    private static final String CLIENT_IP_HEADER = "X-Bvisionry-Client-Ip";
    private static final String PROXY_SECRET_HEADER = "X-Bvisionry-Proxy-Secret";

    private final int trustedProxyHops;
    private final String proxySharedSecret;

    public ClientIpResolver(
            @Value("${bvisionry.security.trusted-proxy-hops:0}") int trustedProxyHops,
            @Value("${bvisionry.proxy.shared-secret:}") String proxySharedSecret) {
        this.trustedProxyHops = Math.max(0, trustedProxyHops);
        this.proxySharedSecret = proxySharedSecret;
    }

    /**
     * Returns the real client IP. When the request carries a valid BFF proxy secret
     * ({@code X-Bvisionry-Proxy-Secret} matching the configured shared secret) the
     * BFF-supplied {@code X-Bvisionry-Client-Ip} is authoritative and returned as-is.
     * Otherwise this is the X-Forwarded-For entry {@code trustedProxyHops} positions
     * from the right when behind trusted proxies, or the raw
     * {@link HttpServletRequest#getRemoteAddr()} (direct connection / no chain).
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();

        // Authoritative path: trust the BFF-supplied client IP only when the BFF has
        // authenticated itself with the matching shared secret. Without a configured
        // secret (local dev) or on a mismatch we ignore the header entirely, so the
        // client-controlled X-Bvisionry-Client-Ip can never be spoofed on its own.
        if (proxySharedSecret != null && !proxySharedSecret.isBlank()) {
            String presentedSecret = request.getHeader(PROXY_SECRET_HEADER);
            if (proxySharedSecret.equals(presentedSecret)) {
                String forwardedClientIp = request.getHeader(CLIENT_IP_HEADER);
                if (forwardedClientIp != null && !forwardedClientIp.isBlank()) {
                    return forwardedClientIp.trim();
                }
            }
        }

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
        // expected), the index would point at — or before — a client-controlled
        // leftmost entry, which is exactly the spoofable position we must never
        // trust. Fall back to the raw TCP peer (remoteAddr) instead.
        int index = entries.length - trustedProxyHops;
        if (index < 0) {
            return remoteAddr;
        }
        return entries[index];
    }
}
