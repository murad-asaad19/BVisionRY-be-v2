package com.bvisionry.coaching.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.bvisionry.coaching.domain.CoachProfile;

/**
 * The coach's own profile row, keyed by their {@code users(id)}.
 *
 * <p>{@code findById} needs no {@code require*} guard here and the ArchUnit
 * tenancy rule agrees ({@code CoachProfile} carries no org field): the id is
 * always the authenticated principal's, never a value a request supplied, so
 * there is no other tenant's row to reach.
 */
public interface CoachProfileRepository extends JpaRepository<CoachProfile, UUID> {
}
