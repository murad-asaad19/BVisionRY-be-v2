package com.bvisionry.certificate.service;

import java.io.ByteArrayOutputStream;
import java.security.SecureRandom;
import java.time.OffsetDateTime;
import java.time.Year;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;
import org.xhtmlrenderer.pdf.ITextRenderer;

import com.bvisionry.auth.entity.User;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.catalog.repository.CourseRepository;
import com.bvisionry.catalog.web.CourseNotFoundException;
import com.bvisionry.certificate.domain.Certificate;
import com.bvisionry.certificate.dto.CertificateDto;
import com.bvisionry.certificate.dto.CertificateVerifyDto;
import com.bvisionry.certificate.repository.CertificateRepository;
import com.bvisionry.common.exception.ReportGenerationException;
import com.bvisionry.enrollment.domain.Enrollment;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * Application service for certificate issuance, PDF generation, and
 * public verification.
 *
 * <p><strong>Issuance ({@link #issue}):</strong> Idempotent find-or-create by
 * {@code enrollment_id}. A unique {@code BV-YYYY-XXXXXX} certificate number is
 * generated with a retry loop to handle the (rare) unique-constraint collision.
 *
 * <p><strong>PDF ({@link #generatePdf}):</strong> Reuses the same
 * Thymeleaf + Flying-Saucer pipeline as
 * {@link com.bvisionry.reporting.service.PdfReportService}, rendering the
 * {@code certificate} Thymeleaf template with Dosis fonts registered.
 *
 * <p><strong>Verify ({@link #verify}):</strong> Public, no-auth lookup that
 * returns a safe {@link CertificateVerifyDto}. When {@code valid=false} all
 * other fields are {@code null}.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class CertificateService {

    private static final String ALPHABET = "ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789";
    private static final int SUFFIX_LENGTH = 6;
    private static final int MAX_NUMBER_RETRIES = 10;

    /**
     * Shared cryptographically-strong RNG for certificate-number suffixes.
     * SecureRandom (not {@link java.util.Random}) is used so the public
     * {@code BV-YYYY-XXXXXX} identifier is not predictable/enumerable from a
     * seed observable in the issuance sequence. SecureRandom is thread-safe, so
     * a single static instance is reused rather than allocating per call.
     */
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final DateTimeFormatter ISSUED_DATE_FMT =
            DateTimeFormatter.ofPattern("MMMM d, yyyy", Locale.ENGLISH);

    private final CertificateRepository certificates;
    private final CourseRepository courses;
    private final TemplateEngine templateEngine;

    // -------------------------------------------------------------------------
    // Issue (idempotent find-or-create)
    // -------------------------------------------------------------------------

    /**
     * Issues a certificate for the given enrollment, or returns the existing
     * one if it was already issued. Snapshots {@code courseTitle} from the
     * {@link Course} and {@code learnerName} from the {@link User} embedded in
     * the enrollment record.
     *
     * <p>This method is <strong>public</strong> so the orchestrator can wire it
     * into the completion callback without reflection.
     *
     * @param enrollment  the completed enrollment
     * @param course      the course entity (to snapshot the title)
     * @param learner     the learner user entity (to snapshot the display name)
     * @return the new or existing {@link Certificate}
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public Certificate issue(Enrollment enrollment, Course course, User learner) {
        return certificates.findByEnrollmentId(enrollment.getId())
                .orElseGet(() -> create(enrollment, course, learner));
    }

    private Certificate create(Enrollment enrollment, Course course, User learner) {
        Certificate cert = new Certificate();
        cert.setEnrollmentId(enrollment.getId());
        cert.setUserId(enrollment.getUserId());
        cert.setCourseId(enrollment.getCourseId());
        cert.setCourseTitle(course.getTitle());
        cert.setLearnerName(learner.getName());
        cert.setIssuedAt(OffsetDateTime.now());

        for (int attempt = 0; attempt < MAX_NUMBER_RETRIES; attempt++) {
            cert.setCertificateNumber(generateNumber());
            try {
                return certificates.saveAndFlush(cert);
            } catch (DataIntegrityViolationException ex) {
                // A DataIntegrityViolationException here can mean one of two distinct
                // unique constraints fired: uq_certificate_enrollment (one cert per
                // enrollment) or the certificate_number uniqueness. Disambiguate by
                // re-reading the enrollment: if a certificate now exists for this
                // enrollment, a concurrent issuer won the race — return it idempotently
                // instead of burning all retries re-violating the enrollment constraint.
                var existing = certificates.findByEnrollmentId(enrollment.getId());
                if (existing.isPresent()) {
                    log.info("Certificate already issued for enrollment {} by a concurrent request; returning existing",
                            enrollment.getId());
                    return existing.get();
                }
                // No certificate for this enrollment yet, so this was a genuine (rare)
                // certificate_number collision — retry with a fresh random suffix.
                log.warn("Certificate number collision on attempt {}; retrying", attempt + 1);
            }
        }
        throw new ReportGenerationException(
                "Failed to generate unique certificate number after " + MAX_NUMBER_RETRIES + " attempts",
                new IllegalStateException("Exhausted certificate number retries"));
    }

    private String generateNumber() {
        int year = Year.now().getValue();
        StringBuilder sb = new StringBuilder(SUFFIX_LENGTH);
        for (int i = 0; i < SUFFIX_LENGTH; i++) {
            sb.append(ALPHABET.charAt(RANDOM.nextInt(ALPHABET.length())));
        }
        return "BV-" + year + "-" + sb;
    }

    // -------------------------------------------------------------------------
    // PDF generation
    // -------------------------------------------------------------------------

    /**
     * Generates a branded PDF certificate for {@code cert}.
     *
     * @param cert      the certificate entity (never null)
     * @param showNames {@code true} to show the learner's real name;
     *                  {@code false} to render "Member" instead (privacy mode)
     * @return PDF bytes
     */
    public byte[] generatePdf(Certificate cert, boolean showNames) {
        String displayName = showNames ? cert.getLearnerName() : "Member";

        String issuedAtFormatted = cert.getIssuedAt() != null
                ? cert.getIssuedAt().format(ISSUED_DATE_FMT)
                : OffsetDateTime.now().format(ISSUED_DATE_FMT);

        Context ctx = new Context(Locale.ENGLISH);
        ctx.setVariable("learnerName", displayName);
        ctx.setVariable("courseTitle", cert.getCourseTitle());
        ctx.setVariable("certificateNumber", cert.getCertificateNumber());
        ctx.setVariable("issuedAtFormatted", issuedAtFormatted);
        ctx.setVariable("issuedAt", cert.getIssuedAt());
        ctx.setVariable("showNames", showNames);

        String html = templateEngine.process("certificate", ctx);

        try (ByteArrayOutputStream os = new ByteArrayOutputStream()) {
            ITextRenderer renderer = new ITextRenderer();

            // Register Dosis fonts — mirrors PdfReportService exactly.
            registerFont(renderer, "fonts/Dosis-Regular.ttf");
            registerFont(renderer, "fonts/Dosis-SemiBold.ttf");
            registerFont(renderer, "fonts/Dosis-Bold.ttf");

            renderer.setDocumentFromString(html);
            renderer.layout();
            renderer.createPDF(os);

            log.info("Generated certificate PDF for number {} ({} bytes)",
                    cert.getCertificateNumber(), os.size());
            return os.toByteArray();
        } catch (Exception e) {
            log.error("Failed to generate certificate PDF for {}: {}", cert.getCertificateNumber(), e.getMessage(), e);
            throw new ReportGenerationException("Certificate PDF generation failed", e);
        }
    }

    private void registerFont(ITextRenderer renderer, String resourcePath) {
        try {
            var resource = getClass().getClassLoader().getResource(resourcePath);
            if (resource != null) {
                renderer.getFontResolver().addFont(
                        resource.toExternalForm(),
                        "Dosis",
                        com.lowagie.text.pdf.BaseFont.IDENTITY_H,
                        true,
                        null);
            } else {
                log.warn("Certificate font resource not found: {}", resourcePath);
            }
        } catch (Exception e) {
            log.warn("Failed to register certificate font {}: {}", resourcePath, e.getMessage());
        }
    }

    // -------------------------------------------------------------------------
    // Public verification
    // -------------------------------------------------------------------------

    /**
     * Looks up a certificate by its human-readable number.
     * Returns {@link CertificateVerifyDto#invalid()} if the number is unknown.
     * This method is intentionally unauthenticated.
     */
    @Transactional(readOnly = true)
    public CertificateVerifyDto verify(String certificateNumber) {
        return certificates.findByCertificateNumber(certificateNumber)
                .map(c -> new CertificateVerifyDto(
                        true,
                        c.getCertificateNumber(),
                        c.getCourseTitle(),
                        c.getLearnerName(),
                        c.getIssuedAt()))
                .orElseGet(CertificateVerifyDto::invalid);
    }

    // -------------------------------------------------------------------------
    // Read helpers used by the controller
    // -------------------------------------------------------------------------

    /**
     * Finds the certificate for a given user + course pair, or returns empty.
     */
    @Transactional(readOnly = true)
    public java.util.Optional<Certificate> findForUserAndCourse(UUID userId, UUID courseId) {
        return certificates.findByUserIdAndCourseId(userId, courseId);
    }

    /**
     * Resolves the course by slug (published only). Throws
     * {@link CourseNotFoundException} if not found.
     */
    @Transactional(readOnly = true)
    public Course resolveCourse(String slug) {
        return courses.findBySlug(slug)
                .orElseThrow(() -> new CourseNotFoundException(slug));
    }

    /**
     * Maps a {@link Certificate} to its JSON DTO.
     *
     * <p>{@code enrollmentId} and {@code courseId} are null-safe: a certificate
     * is a permanent snapshot whose FK columns are nulled by the DB (ON DELETE
     * SET NULL) when the source enrollment/course is hard-deleted. The snapshot
     * scalars (number, courseTitle, learnerName, issuedAt) always remain.
     */
    public CertificateDto toDto(Certificate c) {
        return new CertificateDto(
                c.getId().toString(),
                c.getEnrollmentId() != null ? c.getEnrollmentId().toString() : null,
                c.getCourseId() != null ? c.getCourseId().toString() : null,
                c.getCertificateNumber(),
                c.getCourseTitle(),
                c.getLearnerName(),
                c.getIssuedAt());
    }
}
