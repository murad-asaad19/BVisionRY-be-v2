package com.bvisionry.common.pdf.template;

import com.bvisionry.common.pdf.template.dto.PdfTemplateDto;
import com.bvisionry.common.pdf.template.dto.PdfTemplatePreviewRequest;
import com.bvisionry.common.pdf.template.dto.PdfTemplateSummaryDto;
import com.bvisionry.common.pdf.template.dto.PdfTemplateUpdateRequest;
import com.bvisionry.common.security.CurrentUserAccessor;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

/**
 * Super-admin console for the editable PDF templates — the same authoring
 * model as {@code /api/admin/email-templates}: platform-global, one entry per
 * key, defaults shipped in code, only customizations stored.
 */
@RestController
@RequestMapping("/api/admin/pdf-templates")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class PdfTemplateController {

    private final CurrentUserAccessor currentUser;
    private final PdfTemplateService service;

    @GetMapping
    public ResponseEntity<List<PdfTemplateSummaryDto>> list() {
        return ResponseEntity.ok(service.listAll());
    }

    @GetMapping("/{key}")
    public ResponseEntity<PdfTemplateDto> get(@PathVariable PdfTemplateKey key) {
        return ResponseEntity.ok(service.get(key));
    }

    @PutMapping("/{key}")
    public ResponseEntity<PdfTemplateDto> update(@PathVariable PdfTemplateKey key,
                                                 @Valid @RequestBody PdfTemplateUpdateRequest request) {
        return ResponseEntity.ok(service.update(key, request, currentUser.require().userId()));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<PdfTemplateDto> reset(@PathVariable PdfTemplateKey key) {
        return ResponseEntity.ok(service.reset(key));
    }

    /** Renders the in-flight values over sample data and returns the PDF itself. */
    @PostMapping("/{key}/preview")
    public ResponseEntity<byte[]> preview(@PathVariable PdfTemplateKey key,
                                          @Valid @RequestBody PdfTemplatePreviewRequest request) {
        byte[] pdf = service.preview(key, request.values());
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "inline; filename=\"preview.pdf\"")
                .contentType(MediaType.APPLICATION_PDF)
                .body(pdf);
    }
}
