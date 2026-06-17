package com.bvisionry.testimonial;

import com.bvisionry.testimonial.entity.Testimonial;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface TestimonialRepository extends JpaRepository<Testimonial, UUID> {

    /** Public marquee: only published, ordered for display. */
    List<Testimonial> findByPublishedTrueOrderByDisplayOrderAscCreatedAtAsc();

    /** Admin console: everything, ordered the same way. */
    List<Testimonial> findAllByOrderByDisplayOrderAscCreatedAtAsc();

    @Query("select max(t.displayOrder) from Testimonial t")
    Optional<Integer> findMaxDisplayOrder();
}
