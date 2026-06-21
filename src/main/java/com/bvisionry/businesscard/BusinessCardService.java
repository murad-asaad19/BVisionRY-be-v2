package com.bvisionry.businesscard;

import com.bvisionry.businesscard.dto.BusinessCardLinkPayload;
import com.bvisionry.businesscard.dto.BusinessCardRequest;
import com.bvisionry.businesscard.dto.BusinessCardResponse;
import com.bvisionry.businesscard.dto.PublicBusinessCardResponse;
import com.bvisionry.businesscard.entity.BusinessCard;
import com.bvisionry.businesscard.entity.CardLink;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.media.MediaService;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class BusinessCardService {

    /** Max link buttons per card — a generous bound so the UI stays usable. */
    private static final int MAX_LINKS = 12;

    private final BusinessCardRepository repository;
    private final MediaService mediaService;

    /** Admin console — every card, raw marker + resolved preview URL. */
    @Transactional(readOnly = true)
    public List<BusinessCardResponse> listAll() {
        return repository.findAllByOrderByDisplayOrderAscCreatedAtAsc().stream()
                .map(this::toAdminResponse)
                .toList();
    }

    /** Public page — a single published card, photo resolved to a browsable URL. */
    @Transactional(readOnly = true)
    public PublicBusinessCardResponse getPublishedBySlug(String slug) {
        String normalized = slug == null ? "" : slug.trim().toLowerCase(Locale.ROOT);
        BusinessCard card = repository.findBySlugAndPublishedTrue(normalized)
                .orElseThrow(() -> new ResourceNotFoundException("Business card", normalized));
        return PublicBusinessCardResponse.from(card, mediaService.resolveUrl(card.getPhotoUrl()));
    }

    @Transactional
    public BusinessCardResponse create(BusinessCardRequest request) {
        BusinessCard card = new BusinessCard();
        applyFields(card, request, true);
        return toAdminResponse(repository.save(card));
    }

    @Transactional
    public BusinessCardResponse update(UUID id, BusinessCardRequest request) {
        BusinessCard card = findOrThrow(id);
        applyFields(card, request, false);
        return toAdminResponse(repository.save(card));
    }

    @Transactional
    public void delete(UUID id) {
        repository.delete(findOrThrow(id));
    }

    // ── internals ──────────────────────────────────────────────────────────────

    private void applyFields(BusinessCard card, BusinessCardRequest request, boolean isCreate) {
        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BadRequestException("Name cannot be blank");
        }
        card.setName(name);

        // Slug: explicit value wins; otherwise derive from name on create, or keep
        // the existing slug on update so a live QR link never silently breaks.
        String slug;
        if (request.slug() != null && !request.slug().isBlank()) {
            slug = slugify(request.slug());
        } else if (isCreate) {
            slug = slugify(name);
        } else {
            slug = card.getSlug();
        }
        ensureSlugUnique(slug, card.getId());
        card.setSlug(slug);

        card.setTitle(trimToNull(request.title()));
        card.setTagline(trimToNull(request.tagline()));
        card.setTaglineBold(trimToNull(request.taglineBold()));
        card.setPhotoUrl(trimToNull(request.photoUrl()));
        card.setLinks(normalizeLinks(request.links()));

        if (request.published() != null) {
            card.setPublished(request.published());
        } else if (isCreate) {
            card.setPublished(true);
        }
        if (request.displayOrder() != null) {
            card.setDisplayOrder(request.displayOrder());
        } else if (isCreate) {
            card.setDisplayOrder(nextDisplayOrder());
        }
    }

    private void ensureSlugUnique(String slug, UUID id) {
        boolean taken = (id == null)
                ? repository.existsBySlug(slug)
                : repository.existsBySlugAndIdNot(slug, id);
        if (taken) {
            throw new DuplicateResourceException("A business card with the link '" + slug + "' already exists");
        }
    }

    /** Lowercase, hyphenate non-alphanumerics, trim leading/trailing hyphens. */
    private static String slugify(String input) {
        String slug = input.trim().toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9]+", "-")
                .replaceAll("(^-+)|(-+$)", "");
        if (slug.isEmpty()) {
            slug = "card";
        }
        if (slug.length() > 80) {
            slug = slug.substring(0, 80).replaceAll("-+$", "");
        }
        return slug;
    }

    /** Drop blank links, trim, default a blank icon to LINK, cap the count. */
    private static List<CardLink> normalizeLinks(List<BusinessCardLinkPayload> links) {
        if (links == null) {
            return new ArrayList<>();
        }
        return links.stream()
                .filter(l -> l != null
                        && l.url() != null && !l.url().isBlank()
                        && l.label() != null && !l.label().isBlank())
                .limit(MAX_LINKS)
                .map(l -> new CardLink(
                        (l.icon() == null || l.icon().isBlank()) ? "LINK" : l.icon().trim().toUpperCase(Locale.ROOT),
                        l.label().trim(),
                        l.url().trim()))
                .collect(java.util.stream.Collectors.toCollection(ArrayList::new));
    }

    private BusinessCardResponse toAdminResponse(BusinessCard card) {
        return BusinessCardResponse.from(card, mediaService.resolveUrl(card.getPhotoUrl()));
    }

    private BusinessCard findOrThrow(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Business card", id.toString()));
    }

    private int nextDisplayOrder() {
        return repository.findMaxDisplayOrder().orElse(-1) + 1;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
