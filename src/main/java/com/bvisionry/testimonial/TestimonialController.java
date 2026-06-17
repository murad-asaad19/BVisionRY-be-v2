package com.bvisionry.testimonial;

import com.bvisionry.testimonial.dto.CreateTestimonialRequest;
import com.bvisionry.testimonial.dto.PublicTestimonialResponse;
import com.bvisionry.testimonial.dto.TestimonialResponse;
import com.bvisionry.testimonial.dto.UpdateTestimonialRequest;
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

@RestController
@RequestMapping("/api/v1/testimonials")
@RequiredArgsConstructor
public class TestimonialController {

    private final TestimonialService testimonialService;

    /** Public homepage marquee — published testimonials only. Open to anyone. */
    @GetMapping
    public ResponseEntity<List<PublicTestimonialResponse>> listPublished() {
        return ResponseEntity.ok(testimonialService.listPublished());
    }

    /** Admin console — every testimonial including hidden ones. */
    @GetMapping("/admin")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<List<TestimonialResponse>> listAll() {
        return ResponseEntity.ok(testimonialService.listAll());
    }

    @PostMapping
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<TestimonialResponse> create(@Valid @RequestBody CreateTestimonialRequest request) {
        return ResponseEntity.status(HttpStatus.CREATED).body(testimonialService.create(request));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<TestimonialResponse> update(@PathVariable UUID id,
                                                      @Valid @RequestBody UpdateTestimonialRequest request) {
        return ResponseEntity.ok(testimonialService.update(id, request));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public ResponseEntity<Void> delete(@PathVariable UUID id) {
        testimonialService.delete(id);
        return ResponseEntity.noContent().build();
    }
}
