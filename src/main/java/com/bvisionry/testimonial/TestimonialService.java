package com.bvisionry.testimonial;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.media.MediaService;
import com.bvisionry.testimonial.dto.CreateTestimonialRequest;
import com.bvisionry.testimonial.dto.PublicTestimonialResponse;
import com.bvisionry.testimonial.dto.TestimonialResponse;
import com.bvisionry.testimonial.dto.UpdateTestimonialRequest;
import com.bvisionry.testimonial.entity.Testimonial;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class TestimonialService {

    private final TestimonialRepository testimonialRepository;
    private final MediaService mediaService;

    /** Public marquee — published only, photos resolved to browsable URLs. */
    @Transactional(readOnly = true)
    public List<PublicTestimonialResponse> listPublished() {
        return testimonialRepository.findByPublishedTrueOrderByDisplayOrderAscCreatedAtAsc().stream()
                .map(t -> PublicTestimonialResponse.from(t, mediaService.resolveUrl(t.getPhotoUrl())))
                .toList();
    }

    /** Admin console — every testimonial, raw marker + resolved preview URL. */
    @Transactional(readOnly = true)
    public List<TestimonialResponse> listAll() {
        return testimonialRepository.findAllByOrderByDisplayOrderAscCreatedAtAsc().stream()
                .map(this::toAdminResponse)
                .toList();
    }

    @Transactional
    public TestimonialResponse create(CreateTestimonialRequest request) {
        Testimonial t = new Testimonial();
        t.setName(request.name().trim());
        t.setTitle(trimToNull(request.title()));
        t.setQuote(request.quote().trim());
        t.setYear(request.year());
        t.setRating(request.rating());
        t.setPhotoUrl(trimToNull(request.photoUrl()));
        t.setPublished(request.published() == null || request.published());
        t.setDisplayOrder(request.displayOrder() != null ? request.displayOrder() : nextDisplayOrder());
        return toAdminResponse(testimonialRepository.save(t));
    }

    /**
     * Full replace of the editable fields. The admin edit form always submits the
     * complete record, so a {@code null}/blank optional field (title, year,
     * photoUrl) is an explicit clear — not "leave unchanged". Required fields
     * (name, quote, rating) are validated; published/displayOrder keep their
     * current value only if the caller omits them entirely.
     */
    @Transactional
    public TestimonialResponse update(UUID id, UpdateTestimonialRequest request) {
        Testimonial t = findOrThrow(id);

        String name = request.name() == null ? "" : request.name().trim();
        if (name.isEmpty()) {
            throw new BadRequestException("Name cannot be blank");
        }
        String quote = request.quote() == null ? "" : request.quote().trim();
        if (quote.isEmpty()) {
            throw new BadRequestException("Testimonial cannot be blank");
        }
        if (request.rating() == null) {
            throw new BadRequestException("Rating is required");
        }

        t.setName(name);
        t.setQuote(quote);
        t.setRating(request.rating());
        t.setTitle(trimToNull(request.title()));
        t.setYear(request.year());
        t.setPhotoUrl(trimToNull(request.photoUrl()));
        if (request.published() != null) {
            t.setPublished(request.published());
        }
        if (request.displayOrder() != null) {
            t.setDisplayOrder(request.displayOrder());
        }
        return toAdminResponse(testimonialRepository.save(t));
    }

    @Transactional
    public void delete(UUID id) {
        Testimonial t = findOrThrow(id);
        testimonialRepository.delete(t);
    }

    private TestimonialResponse toAdminResponse(Testimonial t) {
        return TestimonialResponse.from(t, mediaService.resolveUrl(t.getPhotoUrl()));
    }

    private Testimonial findOrThrow(UUID id) {
        return testimonialRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Testimonial", id.toString()));
    }

    private int nextDisplayOrder() {
        return testimonialRepository.findMaxDisplayOrder().orElse(-1) + 1;
    }

    private static String trimToNull(String value) {
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
