package com.bvisionry.calendar.domain;

import java.time.OffsetDateTime;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.PrePersist;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * The calendar a coach connected to Bvisionry (V216, sessions spec v2 §2/§7).
 *
 * <p>The PK IS the coach's {@code users(id)}: one connection per coach, and
 * "may I write this row?" has exactly one answer — only when the id equals the
 * authenticated principal. Same shape, and the same reasoning, as
 * {@code coach_profiles}; soft-coupled to identity by UUID so this slice never
 * imports {@code auth}.
 *
 * <p>{@code refreshTokenEnc} is ciphertext from
 * {@link com.bvisionry.common.crypto.SecretEncryptionService} and is decrypted
 * only inside the provider call that needs it — nothing else in this slice, and
 * nothing outside it, ever sees the plaintext grant.
 *
 * <p>{@code lastError} is the whole failure story the coach is shown: every
 * calendar operation fails soft (a booking must never be lost because Google
 * hiccuped), so without a recorded error a broken connection would be silent.
 */
@Entity
@Table(name = "coach_calendar_connections")
@Getter
@Setter
public class CoachCalendarConnection {

    /** Google's own name for "the account's default calendar". */
    public static final String DEFAULT_CALENDAR_ID = "primary";

    @Id
    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    /** {@code CalendarProvider.id()} — {@code GOOGLE} today, the seam for a second one. */
    @Column(name = "provider", nullable = false, length = 20)
    private String provider;

    /** The Google account the coach consented with — shown so they can tell which one it is. */
    @Column(name = "account_email", nullable = false, length = 320)
    private String accountEmail;

    @Column(name = "refresh_token_enc", nullable = false)
    private String refreshTokenEnc;

    @Column(name = "calendar_id", nullable = false, length = 255)
    private String calendarId = DEFAULT_CALENDAR_ID;

    @Column(name = "connected_at", nullable = false)
    private OffsetDateTime connectedAt;

    /** Last successful provider round-trip; null until the first event is written. */
    @Column(name = "last_synced_at")
    private OffsetDateTime lastSyncedAt;

    /** Null when the last operation succeeded. */
    @Column(name = "last_error")
    private String lastError;

    @PrePersist
    void onCreate() {
        if (connectedAt == null) {
            connectedAt = OffsetDateTime.now();
        }
    }
}
