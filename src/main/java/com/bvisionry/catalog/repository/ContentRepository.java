package com.bvisionry.catalog.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.bvisionry.catalog.domain.Content;

/**
 * Access to {@link Content}.
 */
@Repository
public interface ContentRepository extends JpaRepository<Content, UUID> {

    List<Content> findBySectionIdOrderBySequenceAsc(UUID sectionId);

    /** Highest existing sequence in a section, for appending a new content item. */
    @Query("SELECT COALESCE(MAX(c.sequence), -1) FROM Content c WHERE c.section.id = :sectionId")
    int findMaxSequence(@Param("sectionId") UUID sectionId);

    /**
     * Loads a content item with its section and the section's course eagerly fetched,
     * so the caller can verify cross-course ownership without triggering lazy loads.
     */
    @Query("SELECT c FROM Content c JOIN FETCH c.section s JOIN FETCH s.course WHERE c.id = :id")
    java.util.Optional<Content> findByIdWithSectionAndCourse(@Param("id") UUID id);

    /** Total lessons (content items) across all sections of a course. */
    @Query("SELECT COUNT(c) FROM Content c WHERE c.section.course.id = :courseId")
    long countByCourseId(@Param("courseId") UUID courseId);
}
