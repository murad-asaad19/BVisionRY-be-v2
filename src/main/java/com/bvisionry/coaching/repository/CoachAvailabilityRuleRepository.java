package com.bvisionry.coaching.repository;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.bvisionry.coaching.domain.CoachAvailabilityRule;

/**
 * The coach's weekly windows (V215, spec §2.1). Every method is keyed by
 * {@code coachId}, which on the write path is always the authenticated
 * principal — there is no org column and no bare-ID load, so no {@code require*}
 * guard applies.
 */
public interface CoachAvailabilityRuleRepository extends JpaRepository<CoachAvailabilityRule, UUID> {

    List<CoachAvailabilityRule> findByCoachIdOrderByWeekdayAscStartTimeAsc(UUID coachId);

    void deleteByCoachId(UUID coachId);

    /**
     * Spec §4: only a coach who has published at least one window is offered to
     * a founder as bookable. Ids rather than entities — the caller filters a
     * coach LIST and never touches the windows themselves.
     */
    @Query("select distinct r.coachId from CoachAvailabilityRule r where r.coachId in :coachIds")
    List<UUID> coachIdsWithRules(Collection<UUID> coachIds);
}
