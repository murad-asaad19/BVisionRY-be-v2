package com.bvisionry.exercise.repository;

import com.bvisionry.exercise.entity.ExerciseComment;
import com.bvisionry.exercise.entity.ExerciseCommentStatus;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.List;
import java.util.UUID;

public interface ExerciseCommentRepository extends JpaRepository<ExerciseComment, UUID> {

    List<ExerciseComment> findBySubmissionIdOrderByCreatedAt(UUID submissionId);

    long countBySubmissionIdAndStatusAndParentIsNull(UUID submissionId, ExerciseCommentStatus status);

    /** Open root-comment counts for many submissions in one round-trip. */
    @Query("select c.submission.id, count(c) from ExerciseComment c "
            + "where c.submission.id in :submissionIds and c.status = 'OPEN' and c.parent is null "
            + "group by c.submission.id")
    List<Object[]> countOpenBySubmissionIdIn(@Param("submissionIds") List<UUID> submissionIds);

    /** Rows of this submission that carry at least one comment (save keeps them soft-deleted). */
    @Query("select distinct c.row.id from ExerciseComment c "
            + "where c.submission.id = :submissionId and c.row is not null")
    List<UUID> findCommentedRowIds(@Param("submissionId") UUID submissionId);
}
