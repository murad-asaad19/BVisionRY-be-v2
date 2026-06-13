package com.bvisionry.upgrade;

import com.bvisionry.upgrade.entity.UpgradeRequest;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import java.util.Optional;
import java.util.UUID;

public interface UpgradeRequestRepository extends JpaRepository<UpgradeRequest, UUID> {

    /**
     * Most recent request from a given user — drives both the cooldown gate
     * (compare createdAt against now-24h) and the GET /me/.../latest endpoint
     * that the gate UI reads on mount.
     */
    Optional<UpgradeRequest> findFirstByRequestedBy_IdOrderByCreatedAtDesc(UUID requestedById);

    /**
     * Serializes concurrent request-creation per requester. Acquires an exclusive
     * transaction-level advisory lock keyed on the requester id; the lock blocks
     * other transactions sharing the same key and auto-releases at commit/rollback
     * (no manual unlock). Called at the top of the @Transactional create() so the
     * second of two concurrent POSTs waits for the first to commit, then sees its
     * row and is correctly rejected by the time-window cooldown check.
     *
     * <p>hashtext(text) returns int4, which promotes to the single-key
     * pg_advisory_xact_lock(bigint) overload.
     */
    @Query(value = "SELECT pg_advisory_xact_lock(hashtext(:key))", nativeQuery = true)
    void acquireRequesterLock(@Param("key") String key);
}
