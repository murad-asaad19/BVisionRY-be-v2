package com.bvisionry.certificate.dto;

import java.time.OffsetDateTime;

/**
 * Response for the public certificate-verification endpoint
 * {@code GET /api/v1/certificates/verify/{number}}.
 *
 * <p>When {@code valid} is {@code false} all other fields are {@code null}.
 *
 * <p>This DTO intentionally carries only the certificate's permanent SNAPSHOT
 * scalars ({@code certificateNumber}, {@code courseTitle}, {@code learnerName},
 * {@code issuedAt}) and never dereferences the user/course/enrollment FKs, so
 * verification stays valid even after those references are nulled (ON DELETE SET
 * NULL) by a later deletion of the source user/course/enrollment.
 */
public record CertificateVerifyDto(
        boolean valid,
        String certificateNumber,
        String courseTitle,
        String learnerName,
        OffsetDateTime issuedAt
) {

    /** Convenience factory for an invalid (not-found) result. */
    public static CertificateVerifyDto invalid() {
        return new CertificateVerifyDto(false, null, null, null, null);
    }
}
