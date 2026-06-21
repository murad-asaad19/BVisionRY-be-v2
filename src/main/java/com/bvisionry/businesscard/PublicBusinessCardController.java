package com.bvisionry.businesscard;

import com.bvisionry.businesscard.dto.PublicBusinessCardResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Public, unauthenticated read of a single published business card by slug —
 * backs the {@code /card/{slug}} page. Open to anyone (permitAll in SecurityConfig);
 * a hidden or unknown slug yields 404.
 */
@RestController
@RequestMapping("/api/public/business-cards")
@RequiredArgsConstructor
public class PublicBusinessCardController {

    private final BusinessCardService businessCardService;

    @GetMapping("/by-slug/{slug}")
    public ResponseEntity<PublicBusinessCardResponse> getBySlug(@PathVariable String slug) {
        return ResponseEntity.ok(businessCardService.getPublishedBySlug(slug));
    }
}
