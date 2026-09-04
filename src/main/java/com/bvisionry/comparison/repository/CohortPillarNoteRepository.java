package com.bvisionry.comparison.repository;

import com.bvisionry.comparison.domain.CohortPillarNote;
import org.springframework.data.jpa.repository.JpaRepository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

public interface CohortPillarNoteRepository extends JpaRepository<CohortPillarNote, UUID> {

    List<CohortPillarNote> findByOrgIdAndCohortId(UUID orgId, UUID cohortId);

    Optional<CohortPillarNote> findByOrgIdAndCohortIdAndPillarId(UUID orgId, UUID cohortId, UUID pillarId);
}
