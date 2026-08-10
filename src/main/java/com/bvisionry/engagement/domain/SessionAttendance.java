package com.bvisionry.engagement.domain;

import java.io.Serializable;
import java.time.OffsetDateTime;
import java.util.Objects;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.IdClass;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * A presence row: exists only when an admin ticked the member present, so the
 * roll call naturally starts unticked. §7b: every tick is stamped with
 * {@code marked_at}/{@code marked_by}; re-ticking an already-present member is
 * a no-op that keeps the original stamp. Untick = row deleted.
 */
@Entity
@Table(name = "session_attendance")
@IdClass(SessionAttendance.Key.class)
@Getter
@Setter
public class SessionAttendance {

    @Id
    @Column(name = "session_id", nullable = false, updatable = false)
    private UUID sessionId;

    @Id
    @Column(name = "member_id", nullable = false, updatable = false)
    private UUID memberId;

    @Column(name = "marked_at", nullable = false)
    private OffsetDateTime markedAt;

    @Column(name = "marked_by")
    private UUID markedBy;

    /** Composite key (session × member). */
    public static class Key implements Serializable {
        private UUID sessionId;
        private UUID memberId;

        public Key() {
        }

        public Key(UUID sessionId, UUID memberId) {
            this.sessionId = sessionId;
            this.memberId = memberId;
        }

        @Override
        public boolean equals(Object o) {
            return o instanceof Key k
                    && Objects.equals(sessionId, k.sessionId)
                    && Objects.equals(memberId, k.memberId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(sessionId, memberId);
        }
    }
}
