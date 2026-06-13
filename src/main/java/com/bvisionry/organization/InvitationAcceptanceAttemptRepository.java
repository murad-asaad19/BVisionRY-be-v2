package com.bvisionry.organization;

import com.bvisionry.organization.dto.InvitationAttemptSummary;
import com.bvisionry.organization.entity.InvitationAcceptanceAttempt;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface InvitationAcceptanceAttemptRepository
        extends JpaRepository<InvitationAcceptanceAttempt, UUID> {

    List<InvitationAcceptanceAttempt> findByInvitationIdOrderByAttemptedAtDesc(UUID invitationId);

    long countByInvitationIdAndSuccessFalse(UUID invitationId);

    @Query("""
            SELECT new com.bvisionry.organization.dto.InvitationAttemptSummary(
                a.invitation.id,
                COUNT(a),
                SUM(CASE WHEN a.success = false THEN 1L ELSE 0L END),
                MAX(a.attemptedAt))
            FROM InvitationAcceptanceAttempt a
            WHERE a.invitation.id IN :invitationIds
            GROUP BY a.invitation.id
            """)
    List<InvitationAttemptSummary> summarize(@Param("invitationIds") Collection<UUID> invitationIds);
}
