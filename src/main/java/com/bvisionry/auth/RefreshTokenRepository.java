package com.bvisionry.auth;

import com.bvisionry.auth.entity.RefreshToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface RefreshTokenRepository extends JpaRepository<RefreshToken, UUID> {

    Optional<RefreshToken> findByJti(UUID jti);

    /** Hard-deletes expired rows (suitable for a periodic cleanup job). */
    int deleteByExpiresAtBefore(Instant cutoff);

    /**
     * Marks every still-active refresh token for {@code userId} as revoked. Used
     * on logout, password change, role change, and theft detection.
     */
    @Modifying
    @Query("update RefreshToken rt set rt.revokedAt = :now "
            + "where rt.user.id = :userId and rt.revokedAt is null")
    int revokeAllForUser(@Param("userId") UUID userId, @Param("now") Instant now);
}
