package com.bvisionry.evaluation;

import com.bvisionry.evaluation.entity.SubmissionAiDetection;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.Optional;
import java.util.UUID;

public interface SubmissionAiDetectionRepository extends JpaRepository<SubmissionAiDetection, UUID> {

    Optional<SubmissionAiDetection> findBySubmissionId(UUID submissionId);
}
