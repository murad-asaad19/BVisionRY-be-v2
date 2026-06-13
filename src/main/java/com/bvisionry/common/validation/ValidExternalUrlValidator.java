package com.bvisionry.common.validation;

import jakarta.validation.ConstraintValidator;
import jakarta.validation.ConstraintValidatorContext;

import java.net.InetAddress;
import java.net.URI;
import java.net.URISyntaxException;
import java.net.UnknownHostException;

public class ValidExternalUrlValidator implements ConstraintValidator<ValidExternalUrl, String> {

    @Override
    public boolean isValid(String value, ConstraintValidatorContext context) {
        if (value == null || value.isBlank()) {
            return true;
        }
        return isSafePublicUrl(value);
    }

    /**
     * True if {@code value} is an http(s) URL whose host is not loopback / RFC1918 /
     * link-local / multicast / "localhost". Unresolved hostnames pass — DNS may resolve
     * at runtime and admin-only fields don't warrant strict public-IP checks on send.
     */
    public static boolean isSafePublicUrl(String value) {
        URI uri;
        try {
            uri = new URI(value);
        } catch (URISyntaxException e) {
            return false;
        }

        String scheme = uri.getScheme();
        if (scheme == null || !(scheme.equalsIgnoreCase("http") || scheme.equalsIgnoreCase("https"))) {
            return false;
        }

        String host = uri.getHost();
        if (host == null || host.isBlank()) {
            return false;
        }

        String hostLower = host.toLowerCase();
        if (hostLower.equals("localhost") || hostLower.endsWith(".localhost")) {
            return false;
        }

        try {
            InetAddress addr = InetAddress.getByName(host);
            return !(addr.isLoopbackAddress()
                    || addr.isLinkLocalAddress()
                    || addr.isSiteLocalAddress()
                    || addr.isAnyLocalAddress()
                    || addr.isMulticastAddress());
        } catch (UnknownHostException e) {
            return true;
        }
    }
}
