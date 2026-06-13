package com.bvisionry.catalog.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;
import org.springframework.stereotype.Repository;

import com.bvisionry.catalog.domain.Section;

/**
 * Access to {@link Section}.
 */
@Repository
public interface SectionRepository extends JpaRepository<Section, UUID> {

    List<Section> findByCourseIdOrderBySequenceAsc(UUID courseId);

    /** Sections of a course with their contents eagerly fetched and ordered. */
    @Query("""
            SELECT DISTINCT s FROM Section s
            LEFT JOIN FETCH s.contents
            WHERE s.course.id = :courseId
            ORDER BY s.sequence ASC
            """)
    List<Section> findByCourseIdWithContents(@Param("courseId") UUID courseId);

    /** Highest existing sequence in a course, for appending a new section. */
    @Query("SELECT COALESCE(MAX(s.sequence), -1) FROM Section s WHERE s.course.id = :courseId")
    int findMaxSequence(@Param("courseId") UUID courseId);

    /** Number of sections in a course (authoring list counts). */
    @Query("SELECT COUNT(s) FROM Section s WHERE s.course.id = :courseId")
    long countByCourseId(@Param("courseId") UUID courseId);
}
