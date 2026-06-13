package com.bvisionry.enrollment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bvisionry.enrollment.domain.Enrollment;

@Repository
public interface EnrollmentRepository extends JpaRepository<Enrollment, UUID> {

    Optional<Enrollment> findByUserIdAndCourseId(UUID userId, UUID courseId);

    List<Enrollment> findByUserId(UUID userId);

    boolean existsByUserIdAndCourseId(UUID userId, UUID courseId);
}
