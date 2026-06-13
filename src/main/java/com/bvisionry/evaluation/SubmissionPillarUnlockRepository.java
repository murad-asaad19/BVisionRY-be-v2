package com.bvisionry.evaluation;

import com.bvisionry.evaluation.entity.SubmissionPillarUnlock;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface SubmissionPillarUnlockRepository extends JpaRepository<SubmissionPillarUnlock, UUID> {

    @Query("""
            SELECT u FROM SubmissionPillarUnlock u
            JOIN FETCH u.pillar
            WHERE u.submission.id = :submissionId
            """)
    List<SubmissionPillarUnlock> findBySubmissionId(@Param("submissionId") UUID submissionId);

    @Query("SELECT u.pillar.id FROM SubmissionPillarUnlock u WHERE u.submission.id = :submissionId")
    List<UUID> findUnlockedPillarIds(@Param("submissionId") UUID submissionId);

    int countBySubmissionId(UUID submissionId);

    /**
     * Single bulk DELETE — overrides the Spring Data derived form which would
     * otherwise SELECT every row and DELETE one at a time.
     */
    @Modifying
    @Query("DELETE FROM SubmissionPillarUnlock u WHERE u.submission.id = :submissionId")
    int deleteBySubmissionId(@Param("submissionId") UUID submissionId);
}
