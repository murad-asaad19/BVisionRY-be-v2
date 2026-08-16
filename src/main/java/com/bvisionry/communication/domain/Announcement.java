package com.bvisionry.communication.domain;

import java.time.Instant;
import java.util.UUID;

import com.bvisionry.common.entity.BaseEntity;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.Setter;

/**
 * One cohort broadcast. Plain text only (policy {@code announcement_body:
 * PLAIN_TEXT_PLUS_LINKS}) — a body carrying markup is REFUSED at the API
 * rather than silently rewritten, so nothing downstream has to remember to
 * escape this column.
 *
 * <p>Soft-coupled to the org, the cohort and the author by UUID (the
 * programflow/coaching convention): the FKs exist at the DB level, this slice
 * imports no other feature's entity. {@code orgId} is denormalised so every
 * query carries the tenant equality without a join.
 *
 * <p>{@code flaggedAt}/{@code flaggedBy} record the FIRST member report. There
 * is no moderation workflow: a flag is a visible state for an org admin, and
 * every report also writes an audit row.
 */
@Entity
@Table(name = "announcements")
@Getter
@Setter
public class Announcement extends BaseEntity {

    @Column(name = "org_id", nullable = false, updatable = false)
    private UUID orgId;

    @Column(name = "cohort_id", nullable = false, updatable = false)
    private UUID cohortId;

    /** Null once the author's account is erased — the org's record survives. */
    @Column(name = "author_id", updatable = false)
    private UUID authorId;

    @Column(nullable = false, columnDefinition = "text", updatable = false)
    private String body;

    @Column(name = "flagged_at")
    private Instant flaggedAt;

    @Column(name = "flagged_by")
    private UUID flaggedBy;
}
