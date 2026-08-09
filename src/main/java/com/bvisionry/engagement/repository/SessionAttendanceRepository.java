package com.bvisionry.engagement.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.engagement.domain.SessionAttendance;

public interface SessionAttendanceRepository
        extends JpaRepository<SessionAttendance, SessionAttendance.Key> {

    List<SessionAttendance> findBySessionIdIn(List<UUID> sessionIds);
}
