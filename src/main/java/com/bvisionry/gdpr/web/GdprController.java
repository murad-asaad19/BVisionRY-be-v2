package com.bvisionry.gdpr.web;

import com.bvisionry.gdpr.dto.DeleteAccountRequest;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDate;
import java.util.Map;

/**
 * The data subject's own GDPR rights. Every route is {@code /me} by
 * construction — there is no id to pass, so no request can be aimed at another
 * account.
 */
@RestController
@RequestMapping("/api/gdpr/me")
@RequiredArgsConstructor
@PreAuthorize("isAuthenticated()")
@Tag(name = "GDPR", description = "Self-service data export and account deletion")
public class GdprController {

    private final GdprService gdprService;

    /** Two calls on purpose — see {@link GdprService#recordExport()}: the read is
     *  read-only, the audit row is its own short transaction afterwards. */
    @GetMapping(value = "/export", produces = MediaType.APPLICATION_JSON_VALUE)
    @Operation(summary = "Download everything held about me (GDPR Art. 15 / 20)")
    public ResponseEntity<Map<String, Object>> exportMyData() {
        Map<String, Object> payload = gdprService.exportMyData();
        gdprService.recordExport();
        String filename = "bvisionry-my-data-" + LocalDate.now() + ".json";
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION,
                        ContentDisposition.attachment().filename(filename).build().toString())
                .body(payload);
    }

    /** {@code @ResponseStatus} rather than {@code ResponseEntity<Void>}: springdoc
     *  publishes 200 for the latter, which is a lie the generated client believes. */
    @DeleteMapping
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Permanently delete my account and personal data (GDPR Art. 17)")
    public void deleteMyAccount(@Valid @RequestBody DeleteAccountRequest request) {
        gdprService.deleteMyAccount(request);
    }
}
