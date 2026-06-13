package com.bvisionry.common.web;

import jakarta.servlet.http.HttpServletRequest;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.security.web.util.matcher.IpAddressMatcher;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

/**
 * Resolves the real client IP for a request, honoring {@code X-Forwarded-For}
 * only when the request actually comes through one of the configured trusted
 * proxies. Without this guard, any client could spoof XFF to rotate
 * rate-limit buckets or pollute audit IPs.
 *
 * <p>Configure via {@code bvisionry.security.trusted-proxies} in
 * {@code application.properties} as a comma-separated list of CIDR ranges
 * (e.g. {@code 10.0.0.0/8,172.16.0.0/12}). Default is empty, meaning XFF is
 * never trusted and {@link HttpServletRequest#getRemoteAddr()} always wins.
 */
@Component
public class ClientIpResolver {

    private final List<IpAddressMatcher> trustedProxyMatchers;

    public ClientIpResolver(
            @Value("${bvisionry.security.trusted-proxies:}") String trustedProxiesCsv) {
        this.trustedProxyMatchers = parseTrustedProxies(trustedProxiesCsv);
    }

    /**
     * Returns the first {@code X-Forwarded-For} entry only if {@code RemoteAddr}
     * matches a trusted-proxy CIDR; otherwise returns {@code RemoteAddr} as-is.
     */
    public String resolve(HttpServletRequest request) {
        String remoteAddr = request.getRemoteAddr();
        if (!isFromTrustedProxy(remoteAddr)) {
            return remoteAddr;
        }
        String xForwarded = request.getHeader("X-Forwarded-For");
        if (xForwarded == null || xForwarded.isBlank()) {
            return remoteAddr;
        }
        return xForwarded.split(",")[0].trim();
    }

    private boolean isFromTrustedProxy(String remoteAddr) {
        if (remoteAddr == null || remoteAddr.isBlank() || trustedProxyMatchers.isEmpty()) {
            return false;
        }
        for (IpAddressMatcher matcher : trustedProxyMatchers) {
            if (matcher.matches(remoteAddr)) return true;
        }
        return false;
    }

    private static List<IpAddressMatcher> parseTrustedProxies(String csv) {
        if (csv == null || csv.isBlank()) return Collections.emptyList();
        List<IpAddressMatcher> matchers = new ArrayList<>();
        for (String entry : Arrays.stream(csv.split(",")).map(String::trim).toList()) {
            if (entry.isBlank()) continue;
            matchers.add(new IpAddressMatcher(entry));
        }
        return Collections.unmodifiableList(matchers);
    }
}
