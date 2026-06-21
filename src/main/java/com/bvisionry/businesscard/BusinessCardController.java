package com.bvisionry.businesscard;

import com.bvisionry.businesscard.dto.BusinessCardRequest;
import com.bvisionry.businesscard.dto.BusinessCardResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
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
import java.util.UUID;

/**
 * Admin console CRUD for digital business cards. SUPER_ADMIN only — the public
 * site reads a single published card from the unauthenticated
 * {@code /api/public/business-cards/by-slug/{slug}} endpoint.
 */
@RestController
@RequestMapping("/api/v1/business-cards")
@RequiredArgsConstructor
public class BusinessCardController {

    private final BusinessCardService businessCardService;

    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<List<BusinessCardResponse>> listAll() {
        return ResponseEntity.ok(businessCardService.listAll());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<BusinessCardResponse> create(@Valid @RequestBody BusinessCardRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(businessCardService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<BusinessCardResponse> update(@PathVariable UUID id,
                                                       @Valid @RequestBody BusinessCardRequest request) {
        return ResponseEntity.ok(businessCardService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        businessCardService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
