package com.bvisionry.businesscard;

import com.bvisionry.businesscard.entity.BusinessCard;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface BusinessCardRepository extends JpaRepository<BusinessCard, UUID> {

    /** Admin console: every card, ordered for display. */
    List<BusinessCard> findAllByOrderByDisplayOrderAscCreatedAtAsc();

    /** Public page: a single published card by its slug. */
    Optional<BusinessCard> findBySlugAndPublishedTrue(String slug);

    boolean existsBySlug(String slug);

    boolean existsBySlugAndIdNot(String slug, UUID id);

    @Query("select max(c.displayOrder) from BusinessCard c")
    Optional<Integer> findMaxDisplayOrder();
}
