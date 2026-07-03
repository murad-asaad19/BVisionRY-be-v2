package com.bvisionry.notification.push.dto;

/**
 * The VAPID public key the browser passes as {@code applicationServerKey} to
 * {@code PushManager.subscribe}. Served by the backend (the single owner of
 * the keypair) so the web app needs no build-time copy that could drift.
 */
public record VapidPublicKeyResponse(String publicKey) {
}
