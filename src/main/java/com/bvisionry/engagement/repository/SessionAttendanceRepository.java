package com.bvisionry.engagement.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.bvisionry.engagement.domain.SessionAttendance;

public interface SessionAttendanceRepository
        extends JpaRepository<SessionAttendance, SessionAttendance.Key> {

    List<SessionAttendance> findBySessionIdIn(List<UUID> sessionIds);

    /** V212 flipped the session FK to RESTRICT — a deliberate session delete clears its roll call first. */
    @Modifying
    @Query(value = "DELETE FROM session_attendance WHERE session_id = :sessionId", nativeQuery = true)
    void deleteBySessionId(@Param("sessionId") UUID sessionId);
}
