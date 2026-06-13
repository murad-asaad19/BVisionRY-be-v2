package com.bvisionry.publicassessment.repository;

import com.bvisionry.publicassessment.entity.PublicAssessmentLink;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface PublicAssessmentLinkRepository extends JpaRepository<PublicAssessmentLink, UUID> {

    Optional<PublicAssessmentLink> findByToken(UUID token);

    Page<PublicAssessmentLink> findAllByOrderByCreatedAtDesc(Pageable pageable);

    /**
     * Atomically claims a response slot on the link. Returns 1 when the
     * increment was applied, 0 when the {@code maxResponses} cap is already
     * reached — the caller maps 0 to a 409 so concurrent session-creates can
     * never race past the cap with a read-then-write.
     */
    @Modifying
    @Query("""
            UPDATE PublicAssessmentLink l
            SET l.responseCount = l.responseCount + 1
            WHERE l.id = :id
            AND (l.maxResponses IS NULL OR l.responseCount < l.maxResponses)
            """)
    int incrementResponseCount(@Param("id") UUID id);
}
