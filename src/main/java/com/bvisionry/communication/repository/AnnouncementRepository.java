package com.bvisionry.communication.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.communication.domain.Announcement;

/** Writes for {@link Announcement}; every read shape lives in {@link AnnouncementReadRepository}. */
public interface AnnouncementRepository extends JpaRepository<Announcement, UUID> {
}
