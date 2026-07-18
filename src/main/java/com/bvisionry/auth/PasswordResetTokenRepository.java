package com.bvisionry.auth;

import com.bvisionry.auth.entity.PasswordResetToken;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Modifying;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

public interface PasswordResetTokenRepository extends JpaRepository<PasswordResetToken, UUID> {

    Optional<PasswordResetToken> findByToken(UUID token);

    /**
     * Spends every still-usable token for {@code userId}. Called on successful
     * reset so an older emailed link can't reset the password a second time.
     */
    @Modifying
    @Query("update PasswordResetToken t set t.usedAt = :now "
            + "where t.user.id = :userId and t.usedAt is null")
    int markAllUsedForUser(@Param("userId") UUID userId, @Param("now") Instant now);

    /** Hard-deletes expired rows (suitable for a periodic cleanup job). */
    int deleteByExpiresAtBefore(Instant cutoff);
}
