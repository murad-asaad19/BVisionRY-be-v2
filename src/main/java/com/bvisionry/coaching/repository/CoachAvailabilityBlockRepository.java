package com.bvisionry.coaching.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.coaching.domain.CoachAvailabilityBlock;

/** The coach's blackouts (V215, spec §2.1) — same scoping story as the rules. */
public interface CoachAvailabilityBlockRepository extends JpaRepository<CoachAvailabilityBlock, UUID> {

    List<CoachAvailabilityBlock> findByCoachIdOrderByStartsAtAsc(UUID coachId);

    void deleteByCoachId(UUID coachId);
}
