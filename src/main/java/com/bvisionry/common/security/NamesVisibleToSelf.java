package com.bvisionry.common.security;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a request handler that takes a {@code showNames} flag and
 * <strong>deliberately</strong> does not call {@link ExportNameGuard}, because
 * the only name it can ever reveal is the CALLER'S OWN.
 *
 * <p>The guard exists to stop an ORG_ADMIN unmasking someone else's founders. On
 * a handler that pins the row to the caller before reading a name — the
 * submission-ownership check on {@code /api/my/assessments/...}, the
 * {@code findForUserAndCourse(callerId, …)} lookup behind a certificate — there
 * is no other founder in scope, and applying the guard would refuse a member
 * their own name on their own report.
 *
 * <p>Like {@link AuthorizedInSecurityConfig}, this grants nothing, denies
 * nothing, and is read by nothing at runtime. It records at the call site, where
 * a reviewer will see it, WHICH check pins the row to the caller — the invariant
 * a reader has to verify, and the one a future refactor could quietly remove.
 *
 * <p>The ArchUnit rule {@code showNamesHandlersMustResolveToTheExportNameGuard}
 * accepts this marker as the alternative to a guard call, so a new export
 * handler cannot take {@code showNames} without someone answering the question.
 */
// METHOD-only for the same reason as AuthorizedInSecurityConfig: a TYPE target
// would let one line exempt an entire controller, silently pre-approving every
// export added to it later.
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface NamesVisibleToSelf {

    /**
     * Which check pins the row to the caller, and why that makes the revealed
     * name the caller's own — e.g. "verifySubmissionOwnership: the caller owns
     * this submission".
     */
    String value();
}
