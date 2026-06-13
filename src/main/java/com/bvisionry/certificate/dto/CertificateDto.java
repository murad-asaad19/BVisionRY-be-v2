package com.bvisionry.certificate.dto;

import java.time.OffsetDateTime;

/**
 * JSON representation of a {@link com.bvisionry.certificate.domain.Certificate}
 * returned by {@code GET /api/v1/courses/{slug}/certificate}.
 *
 * <p>{@code enrollmentId} and {@code courseId} are nullable: the certificate is a
 * permanent snapshot whose FK references are nulled by the DB (ON DELETE SET NULL)
 * when the source enrollment/course is deleted. The snapshot identity fields
 * ({@code certificateNumber}, {@code courseTitle}, {@code learnerName},
 * {@code issuedAt}) are always present.
 */
public record CertificateDto(
        String id,
        String enrollmentId,
        String courseId,
        String certificateNumber,
        String courseTitle,
        String learnerName,
        OffsetDateTime issuedAt
) {}
