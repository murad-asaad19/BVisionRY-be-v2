package com.bvisionry.enrollment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bvisionry.enrollment.domain.ContentProgress;

@Repository
public interface ContentProgressRepository extends JpaRepository<ContentProgress, UUID> {

    List<ContentProgress> findByEnrollmentId(UUID enrollmentId);

    Optional<ContentProgress> findByEnrollmentIdAndContentId(UUID enrollmentId, UUID contentId);

    long countByEnrollmentIdAndCompletedTrue(UUID enrollmentId);
}
