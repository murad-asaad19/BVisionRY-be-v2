package com.bvisionry.engagement.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.engagement.domain.Session;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    /**
     * Newest first, undated (UNSCHEDULED, sessions spec v2 §2) rows last —
     * a derived {@code OrderBySessionDateDesc} would sort those NULLs FIRST on
     * Postgres, putting rows nobody has scheduled above the next real session.
     */
    @Query("SELECT s FROM Session s WHERE s.cohortId = :cohortId "
            + "ORDER BY s.sessionDate DESC NULLS LAST")
    List<Session> findByCohortOrderedByDate(@Param("cohortId") UUID cohortId);
}
