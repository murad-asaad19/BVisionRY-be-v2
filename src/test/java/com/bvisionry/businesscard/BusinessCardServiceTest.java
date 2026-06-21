package com.bvisionry.businesscard;

import com.bvisionry.businesscard.dto.BusinessCardLinkPayload;
import com.bvisionry.businesscard.dto.BusinessCardRequest;
import com.bvisionry.businesscard.dto.BusinessCardResponse;
import com.bvisionry.businesscard.entity.BusinessCard;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.media.MediaService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Unit tests for the URL-scheme allowlist enforced on every card link, the
 * backend half of the defense-in-depth stored-XSS guard.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class BusinessCardServiceTest {

    @Mock
    private BusinessCardRepository repository;

    @Mock
    private MediaService mediaService;

    @InjectMocks
    private BusinessCardService service;

    private BusinessCardRequest withLink(String url) {
        return new BusinessCardRequest(
                "card-slug", "Name", null, null, null, null,
                List.of(new BusinessCardLinkPayload("LINK", "Label", url)),
                true, null);
    }

    private void stubSavePath() {
        when(repository.existsBySlug(anyString())).thenReturn(false);
        when(repository.findMaxDisplayOrder()).thenReturn(Optional.of(0));
        when(repository.save(any(BusinessCard.class))).thenAnswer(inv -> inv.getArgument(0));
        when(mediaService.resolveUrl(any())).thenReturn(null);
    }

    @Test
    void create_allowedSchemes_persist() {
        for (String url : List.of(
                "https://www.bvisionry.com",
                "http://example.com",
                "mailto:razan@bvisionry.com",
                "tel:+15551234567",
                // case-insensitive
                "HTTPS://example.com")) {
            stubSavePath();
            BusinessCardResponse response = service.create(withLink(url));
            assertThat(response.links())
                    .extracting(BusinessCardLinkPayload::url)
                    .containsExactly(url);
        }
    }

    @Test
    void create_javascriptScheme_rejected() {
        assertThatThrownBy(() -> service.create(withLink("javascript:alert(1)")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("supported scheme");
    }

    @Test
    void create_dataScheme_rejected() {
        assertThatThrownBy(() -> service.create(withLink("data:text/html,<script>alert(1)</script>")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("supported scheme");
    }

    @Test
    void create_schemelessUrl_rejected() {
        assertThatThrownBy(() -> service.create(withLink("www.example.com")))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("supported scheme");
    }
}
