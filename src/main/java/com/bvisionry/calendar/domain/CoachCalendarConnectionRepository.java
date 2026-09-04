package com.bvisionry.calendar.domain;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

public interface CoachCalendarConnectionRepository extends JpaRepository<CoachCalendarConnection, UUID> {
}
