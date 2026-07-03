package com.bvisionry.certificate.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

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
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.Optional;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

/**
 * Unit tests for {@link CertificateService}: idempotent issuance, the
 * certificate-number collision/concurrent-issuer retry loop, public
 * verification, DTO mapping (incl. the ON-DELETE-SET-NULL snapshot case),
 * and PDF generation incl. the privacy-mode name substitution.
 */
@ExtendWith(MockitoExtension.class)
class CertificateServiceTest {

    @Mock
    private CertificateRepository certificates;
    @Mock
    private CourseRepository courses;
    @Mock
    private TemplateEngine templateEngine;

    private CertificateService service;

    private final UUID enrollmentId = UUID.randomUUID();
    private final UUID userId = UUID.randomUUID();
    private final UUID courseId = UUID.randomUUID();

    private Enrollment enrollment;
    private Course course;
    private User learner;

    @BeforeEach
    void setUp() {
        service = new CertificateService(certificates, courses, templateEngine);

        enrollment = new Enrollment();
        enrollment.setId(enrollmentId);
        enrollment.setUserId(userId);
        enrollment.setCourseId(courseId);

        course = new Course();
        course.setId(courseId);
        course.setTitle("Leadership Essentials");

        learner = new User();
        learner.setId(userId);
        learner.setName("Jane Learner");
    }

    // -------------------------------------------------------------------------
    // issue()
    // -------------------------------------------------------------------------

    @Test
    void issueReturnsExistingCertificateWithoutCreating() {
        Certificate existing = new Certificate();
        existing.setEnrollmentId(enrollmentId);
        when(certificates.findByEnrollmentId(enrollmentId)).thenReturn(Optional.of(existing));

        Certificate result = service.issue(enrollment, course, learner);

        assertThat(result).isSameAs(existing);
        verify(certificates, never()).saveAndFlush(any());
    }

    @Test
    void issueSnapshotsCourseTitleAndLearnerNameAndGeneratesNumber() {
        when(certificates.findByEnrollmentId(enrollmentId)).thenReturn(Optional.empty());
        when(certificates.saveAndFlush(any(Certificate.class))).thenAnswer(inv -> inv.getArgument(0));

        Certificate result = service.issue(enrollment, course, learner);

        ArgumentCaptor<Certificate> saved = ArgumentCaptor.forClass(Certificate.class);
        verify(certificates).saveAndFlush(saved.capture());
        Certificate cert = saved.getValue();
        assertThat(cert.getEnrollmentId()).isEqualTo(enrollmentId);
        assertThat(cert.getUserId()).isEqualTo(userId);
        assertThat(cert.getCourseId()).isEqualTo(courseId);
        assertThat(cert.getCourseTitle()).isEqualTo("Leadership Essentials");
        assertThat(cert.getLearnerName()).isEqualTo("Jane Learner");
        assertThat(cert.getIssuedAt()).isNotNull();
        assertThat(cert.getCertificateNumber())
                .matches("BV-" + Year.now().getValue() + "-[A-Z0-9]{6}");
        assertThat(result).isSameAs(cert);
    }

    @Test
    void issueRetriesWithFreshNumberOnGenuineNumberCollision() {
        when(certificates.findByEnrollmentId(enrollmentId)).thenReturn(Optional.empty());
        when(certificates.saveAndFlush(any(Certificate.class)))
                .thenThrow(new DataIntegrityViolationException("certificate_number unique"))
                .thenAnswer(inv -> inv.getArgument(0));

        Certificate result = service.issue(enrollment, course, learner);

        // Two save attempts; the enrollment was re-read once to disambiguate the constraint.
        verify(certificates, times(2)).saveAndFlush(any(Certificate.class));
        verify(certificates, times(2)).findByEnrollmentId(enrollmentId);
        assertThat(result.getCertificateNumber())
                .matches("BV-" + Year.now().getValue() + "-[A-Z0-9]{6}");
    }

    @Test
    void issueReturnsConcurrentWinnersCertificateOnEnrollmentConstraintRace() {
        Certificate winner = new Certificate();
        winner.setEnrollmentId(enrollmentId);
        winner.setCertificateNumber("BV-2026-AAAAAA");
        when(certificates.findByEnrollmentId(enrollmentId))
                .thenReturn(Optional.empty())   // initial find-or-create miss
                .thenReturn(Optional.of(winner)); // re-read after the constraint fires
        when(certificates.saveAndFlush(any(Certificate.class)))
                .thenThrow(new DataIntegrityViolationException("uq_certificate_enrollment"));

        Certificate result = service.issue(enrollment, course, learner);

        assertThat(result).isSameAs(winner);
        verify(certificates, times(1)).saveAndFlush(any(Certificate.class));
    }

    @Test
    void issueThrowsAfterExhaustingNumberRetries() {
        when(certificates.findByEnrollmentId(enrollmentId)).thenReturn(Optional.empty());
        when(certificates.saveAndFlush(any(Certificate.class)))
                .thenThrow(new DataIntegrityViolationException("certificate_number unique"));

        assertThatThrownBy(() -> service.issue(enrollment, course, learner))
                .isInstanceOf(ReportGenerationException.class)
                .hasMessageContaining("unique certificate number");

        verify(certificates, times(10)).saveAndFlush(any(Certificate.class));
    }

    // -------------------------------------------------------------------------
    // verify()
    // -------------------------------------------------------------------------

    @Test
    void verifyReturnsSnapshotFieldsForKnownNumber() {
        Certificate cert = new Certificate();
        cert.setCertificateNumber("BV-2026-XYZ123");
        cert.setCourseTitle("Leadership Essentials");
        cert.setLearnerName("Jane Learner");
        cert.setIssuedAt(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        when(certificates.findByCertificateNumber("BV-2026-XYZ123")).thenReturn(Optional.of(cert));

        CertificateVerifyDto dto = service.verify("BV-2026-XYZ123");

        assertThat(dto.valid()).isTrue();
        assertThat(dto.certificateNumber()).isEqualTo("BV-2026-XYZ123");
        assertThat(dto.courseTitle()).isEqualTo("Leadership Essentials");
        assertThat(dto.learnerName()).isEqualTo("Jane Learner");
        assertThat(dto.issuedAt()).isEqualTo(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
    }

    @Test
    void verifyReturnsInvalidWithAllNullFieldsForUnknownNumber() {
        when(certificates.findByCertificateNumber(anyString())).thenReturn(Optional.empty());

        CertificateVerifyDto dto = service.verify("BV-2026-NOPE99");

        assertThat(dto.valid()).isFalse();
        assertThat(dto.certificateNumber()).isNull();
        assertThat(dto.courseTitle()).isNull();
        assertThat(dto.learnerName()).isNull();
        assertThat(dto.issuedAt()).isNull();
    }

    // -------------------------------------------------------------------------
    // toDto() / resolveCourse()
    // -------------------------------------------------------------------------

    @Test
    void toDtoIsNullSafeForDetachedSnapshotForeignKeys() {
        // ON DELETE SET NULL: enrollment/course FKs nulled after source deletion.
        Certificate cert = new Certificate();
        cert.setId(UUID.randomUUID());
        cert.setEnrollmentId(null);
        cert.setCourseId(null);
        cert.setCertificateNumber("BV-2026-ABC123");
        cert.setCourseTitle("Deleted Course");
        cert.setLearnerName("Jane Learner");
        cert.setIssuedAt(OffsetDateTime.now());

        CertificateDto dto = service.toDto(cert);

        assertThat(dto.enrollmentId()).isNull();
        assertThat(dto.courseId()).isNull();
        assertThat(dto.certificateNumber()).isEqualTo("BV-2026-ABC123");
        assertThat(dto.courseTitle()).isEqualTo("Deleted Course");
    }

    @Test
    void resolveCourseThrowsForUnknownSlug() {
        when(courses.findBySlug("missing")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> service.resolveCourse("missing"))
                .isInstanceOf(CourseNotFoundException.class);
    }

    // -------------------------------------------------------------------------
    // generatePdf()
    // -------------------------------------------------------------------------

    private Certificate pdfCert() {
        Certificate cert = new Certificate();
        cert.setCertificateNumber("BV-2026-PDF001");
        cert.setCourseTitle("Leadership Essentials");
        cert.setLearnerName("Jane Learner");
        cert.setIssuedAt(OffsetDateTime.parse("2026-01-15T10:00:00Z"));
        return cert;
    }

    @Test
    void generatePdfRendersTemplateWithRealNameWhenShowNamesTrue() {
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("certificate"), any(Context.class)))
                .thenReturn("<html><body>certificate</body></html>");

        byte[] pdf = service.generatePdf(pdfCert(), true);

        assertThat(pdf).isNotEmpty();
        ArgumentCaptor<Context> ctx = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(org.mockito.ArgumentMatchers.eq("certificate"), ctx.capture());
        assertThat(ctx.getValue().getVariable("learnerName")).isEqualTo("Jane Learner");
        assertThat(ctx.getValue().getVariable("showNames")).isEqualTo(true);
    }

    @Test
    void generatePdfSubstitutesMemberInPrivacyMode() {
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("certificate"), any(Context.class)))
                .thenReturn("<html><body>certificate</body></html>");

        byte[] pdf = service.generatePdf(pdfCert(), false);

        assertThat(pdf).isNotEmpty();
        ArgumentCaptor<Context> ctx = ArgumentCaptor.forClass(Context.class);
        verify(templateEngine).process(org.mockito.ArgumentMatchers.eq("certificate"), ctx.capture());
        assertThat(ctx.getValue().getVariable("learnerName")).isEqualTo("Member");
    }

    @Test
    void generatePdfWrapsRendererFailuresInReportGenerationException() {
        // Flying-Saucer requires well-formed XHTML; malformed markup fails the render.
        when(templateEngine.process(org.mockito.ArgumentMatchers.eq("certificate"), any(Context.class)))
                .thenReturn("not xml at all <><");

        assertThatThrownBy(() -> service.generatePdf(pdfCert(), true))
                .isInstanceOf(ReportGenerationException.class)
                .hasMessageContaining("PDF generation failed");
    }
}
