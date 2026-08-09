package com.bvisionry.engagement.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.engagement.domain.Session;

public interface SessionRepository extends JpaRepository<Session, UUID> {

    List<Session> findByOrgIdAndCohortIdOrderBySessionDateDesc(UUID orgId, UUID cohortId);
}
