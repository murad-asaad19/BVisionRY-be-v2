package com.bvisionry.certificate.web;

import java.util.UUID;

import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.catalog.domain.Course;
import com.bvisionry.certificate.domain.Certificate;
import com.bvisionry.certificate.dto.CertificateDto;
import com.bvisionry.certificate.dto.CertificateVerifyDto;
import com.bvisionry.certificate.service.CertificateService;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.web.ClientIpResolver;

import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;

/**
 * Certificate endpoints.
 *
 * <ul>
 *   <li>{@code GET /api/v1/courses/{slug}/certificate} — current user's cert JSON (404 if not earned)</li>
 *   <li>{@code GET /api/v1/courses/{slug}/certificate/pdf?mode=preview|download&showNames=} — PDF download/preview</li>
 *   <li>{@code GET /api/v1/certificates/verify/{number}} — PUBLIC verification endpoint</li>
 * </ul>
 *
 * <p>The CSRF exemptions and {@code permitAll} rule for the public verify
 * endpoint are already configured in {@code SecurityConfig}. Auth endpoints
 * are gated by {@code @PreAuthorize("isAuthenticated()")}.
 */
@RestController
@RequestMapping(path = "/api/v1", produces = MediaType.APPLICATION_JSON_VALUE)
@RequiredArgsConstructor
@Tag(name = "Certificates", description = "Course completion certificates.")
public class CertificateController {

    private final CertificateService certificateService;
    private final RateLimitService rateLimitService;
    private final ClientIpResolver clientIpResolver;

    /**
     * Returns the current user's certificate for the given course slug as JSON.
     * Returns 404 if the user has not yet earned a certificate for this course.
     */
    @GetMapping("/courses/{slug}/certificate")
    @PreAuthorize("isAuthenticated()")
    public CertificateDto getCertificate(@PathVariable String slug) {
        UUID userId = SecurityUtils.getCurrentUserId();
        Course course = certificateService.resolveCourse(slug);

        Certificate cert = certificateService.findForUserAndCourse(userId, course.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Certificate",
                        "user=" + userId + ",course=" + slug));

        return certificateService.toDto(cert);
    }

    /**
     * Returns the certificate as a PDF.
     *
     * @param slug      course slug
     * @param mode      {@code preview} (Content-Disposition: inline) or
     *                  {@code download} (Content-Disposition: attachment)
     * @param showNames {@code true} (default) to show the real learner name;
     *                  {@code false} to show "Member"
     */
    @GetMapping(value = "/courses/{slug}/certificate/pdf",
                produces = MediaType.APPLICATION_PDF_VALUE)
    @PreAuthorize("isAuthenticated()")
    public ResponseEntity<byte[]> getCertificatePdf(
            @PathVariable String slug,
            @RequestParam(defaultValue = "preview") String mode,
            @RequestParam(defaultValue = "true") boolean showNames) {

        UUID userId = SecurityUtils.getCurrentUserId();
        Course course = certificateService.resolveCourse(slug);

        Certificate cert = certificateService.findForUserAndCourse(userId, course.getId())
                .orElseThrow(() -> new ResourceNotFoundException("Certificate",
                        "user=" + userId + ",course=" + slug));

        byte[] pdfBytes = certificateService.generatePdf(cert, showNames);

        String filename = "certificate-" + cert.getCertificateNumber() + ".pdf";
        ContentDisposition disposition = "download".equalsIgnoreCase(mode)
                ? ContentDisposition.attachment().filename(filename).build()
                : ContentDisposition.inline().filename(filename).build();

        HttpHeaders headers = new HttpHeaders();
        headers.setContentDisposition(disposition);
        headers.setContentLength(pdfBytes.length);

        return ResponseEntity.ok()
                .headers(headers)
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdfBytes);
    }

    /**
     * Public endpoint — no authentication required. Returns a
     * {@link CertificateVerifyDto} with {@code valid=true} if the number exists,
     * or {@code valid=false} (all other fields null) if not.
     *
     * <p>Because the endpoint is public and CSRF-exempt and discloses the learner
     * name for a valid certificate, it is rate-limited per real client IP
     * (resolved via the trusted-proxy-aware {@link ClientIpResolver}) so a bot
     * cannot enumerate/probe certificate numbers to harvest learner names. The
     * bucket key is namespaced with {@code "cert-verify:"} so this traffic gets
     * its own per-IP window and never collides with other users of the shared
     * anonymous limiter. Exceeding the limit raises
     * {@code RateLimitExceededException}, which the global handler maps to 429.
     */
    @GetMapping("/certificates/verify/{number}")
    @PreAuthorize("permitAll()")
    public CertificateVerifyDto verifyCertificate(@PathVariable String number,
                                                  HttpServletRequest httpRequest) {
        rateLimitService.checkTryItOutLimit("cert-verify:" + clientIpResolver.resolve(httpRequest));
        return certificateService.verify(number);
    }
}
