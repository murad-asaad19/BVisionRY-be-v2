package com.bvisionry.notification;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.notification.dto.EmailTemplateDto;
import com.bvisionry.notification.dto.EmailTemplatePreviewRequest;
import com.bvisionry.notification.dto.EmailTemplatePreviewResponse;
import com.bvisionry.notification.dto.EmailTemplateSummaryDto;
import com.bvisionry.notification.dto.EmailTemplateTestSendRequest;
import com.bvisionry.notification.dto.EmailTemplateUpdateRequest;
import com.bvisionry.notification.entity.EmailTemplateKey;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
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

@RestController
@RequestMapping("/api/admin/email-templates")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class EmailTemplateController {

    private final EmailTemplateService service;

    @GetMapping
    public ResponseEntity<List<EmailTemplateSummaryDto>> list() {
        return ResponseEntity.ok(service.listAll());
    }

    @GetMapping("/{key}")
    public ResponseEntity<EmailTemplateDto> get(@PathVariable EmailTemplateKey key) {
        return ResponseEntity.ok(service.get(key));
    }

    @PutMapping("/{key}")
    public ResponseEntity<EmailTemplateDto> update(@PathVariable EmailTemplateKey key,
                                                    @Valid @RequestBody EmailTemplateUpdateRequest request) {
        return ResponseEntity.ok(service.update(key, request, SecurityUtils.getCurrentUserId()));
    }

    @DeleteMapping("/{key}")
    public ResponseEntity<EmailTemplateDto> reset(@PathVariable EmailTemplateKey key) {
        return ResponseEntity.ok(service.reset(key));
    }

    @PostMapping("/{key}/preview")
    public ResponseEntity<EmailTemplatePreviewResponse> preview(@PathVariable EmailTemplateKey key,
                                                                 @Valid @RequestBody EmailTemplatePreviewRequest request) {
        return ResponseEntity.ok(service.preview(key, request.values()));
    }

    @PostMapping("/{key}/test-send")
    public ResponseEntity<Void> testSend(@PathVariable EmailTemplateKey key,
                                          @Valid @RequestBody EmailTemplateTestSendRequest request) {
        service.sendTest(key, request.values(), request.toEmail());
        return ResponseEntity.noContent().build();
    }
}
