package com.bvisionry.common.errortracking;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One aggregated unhandled exception, from either tier (see V145).
 *
 * <p>Insert-only: {@code createdAt} from {@link BaseEntity} is the occurrence
 * time and nothing ever updates a row. Deliberately carries no org column — the
 * store is platform-wide and readable only by SUPER_ADMIN, so it is out of scope
 * for tenant scoping (and for the ArchUnit bare-ID-load rule, which keys off an
 * org column).
 */
@Entity
@Table(name = "error_events")
@Getter
@Setter
@NoArgsConstructor
public class ErrorEvent extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private ErrorSource source;

    @Column(name = "exception_type", nullable = false)
    private String exceptionType;

    @Column(columnDefinition = "TEXT")
    private String message;

    @Column(name = "stack_trace", columnDefinition = "TEXT")
    private String stackTrace;

    @Column(name = "request_path", columnDefinition = "TEXT")
    private String requestPath;

    @Column(name = "request_method", length = 16)
    private String requestMethod;

    /** X-Request-Id and nothing else — the cross-tier join key. */
    @Column(name = "request_id", length = 64)
    private String requestId;

    /** Next.js error digest. A different namespace from {@link #requestId}; never a join key. */
    @Column(length = 64)
    private String digest;
}
