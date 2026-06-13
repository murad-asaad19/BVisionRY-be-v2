package com.bvisionry.certificate.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;

import com.bvisionry.certificate.domain.Certificate;

/**
 * Spring Data JPA repository for {@link Certificate}.
 */
@Repository
public interface CertificateRepository extends JpaRepository<Certificate, UUID> {

    Optional<Certificate> findByEnrollmentId(UUID enrollmentId);

    Optional<Certificate> findByCertificateNumber(String certificateNumber);

    boolean existsByEnrollmentId(UUID enrollmentId);

    Optional<Certificate> findByUserIdAndCourseId(UUID userId, UUID courseId);
}
