package com.bvisionry.common.gdpr;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Repository;
import tools.jackson.databind.ObjectMapper;

import java.sql.Timestamp;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * The GDPR data map: every place one user's personal data lives, what the
 * export hands back, and what happens to each data class on erasure.
 *
 * <p><strong>Why this lives in {@code common}.</strong> Two callers must agree
 * on erasure or the divergence IS the bug: the data subject's own Art. 17 flow
 * ({@code gdpr}) and the org admin's permanent delete
 * ({@code organization.MemberService}). The second used to be a bare
 * {@code userRepository.delete}, which aborted outright on any member with a
 * survey response and left certificates, reviews, invitations and audit emails
 * behind. One component, both paths.
 *
 * <p><strong>Why raw SQL rather than the owning features' repositories.</strong>
 * An Art. 15 export by definition reaches into every feature, and the ArchUnit
 * ratchet ({@code noCrossFeatureDependencies}) forbids new feature→feature
 * edges — while {@code commonMustNotDependOnFeatures} forbids this class from
 * importing any of them at all. So it depends on the schema instead: it imports
 * no {@code com.bvisionry} type. The cost is that a NEW personal-data table has
 * to be added here by hand; new COLUMNS are picked up automatically because
 * almost every query is {@code SELECT *}. {@code PersonalDataCoverageTest} is
 * the tripwire for the first case.
 *
 * <p><strong>Identity is the account, never the email address.</strong> Every
 * statement here keys on {@code users.id}. That is not an oversight about the
 * personal data sitting in email-keyed rows — public-flow submissions
 * ({@code submissions.user_id IS NULL} with a {@code respondent_email}), the
 * matching {@code survey_responses}, and pending {@code invitations} — it is
 * the only defensible position while {@code users.email} is unverified.
 * {@code POST /api/auth/register} is {@code permitAll} and mints an ACTIVE
 * account for any address with no existing row, with no mailbox proof anywhere
 * in the flow. An email-keyed export or delete would therefore let anyone
 * register {@code victim@example.com} and, in two calls, read then destroy that
 * address's assessment answers, AI evaluations and invitations across every
 * organisation on the platform. Those rows are consequently out of reach of
 * both the export and the erasure until the request can prove control of the
 * mailbox; {@code PasswordResetService}'s hashed single-use token round-trip
 * (V139/V141) is the pattern already in this codebase that would unlock it. A
 * data subject asking for them today is a manual, human-verified request.
 *
 * <p>The subject's address sitting INSIDE an {@code audit_logs} details blob is
 * in that same bucket, and it is the known gap this flow accepts: a
 * {@code MEMBER_INVITED} row in an org the subject never joined, or a
 * {@code MEMBER_DELETED} row from an earlier admin removal, still names the
 * address after erasure. Reaching them means matching on the email value, which
 * is precisely the attacker-choosable key this class refuses — the arm was
 * removed rather than gated, because gating it on "the subject asked" still
 * lets a squatter be the subject. Erasing those rows is the same manual,
 * human-verified request, and mailbox verification retires both cases at once.
 *
 * <p><strong>Erasure design.</strong> Most of it is expressed by the schema's FK
 * actions (V34 for org delete, V137 for member hard delete), which this class
 * deliberately relies on rather than duplicating:
 * <ul>
 *   <li>{@code ON DELETE CASCADE} — the user's own records (assignments,
 *       submissions, answers, enrolments, quiz attempts, workshop/exercise/
 *       program submissions, team &amp; cohort memberships, notifications,
 *       push subscriptions, refresh &amp; reset tokens) die with the row.</li>
 *   <li>{@code ON DELETE SET NULL} — rows another tenant owns but this user
 *       merely authored or administered (pipelines, surveys, join links,
 *       invitations they sent, assignments they assigned, unlock/archive
 *       attributions) survive with the attribution dropped. Erasing them would
 *       destroy an organisation's records, which is a bug, not compliance.</li>
 * </ul>
 * {@link #erasePersonalDataFor} handles only what the FK actions get wrong.
 *
 * <p><strong>Deliberately retained</strong>, and why:
 * <ul>
 *   <li>{@code audit_logs} rows themselves — the organisation's accountability
 *       trail. Only the identity keys inside {@code details_json} are removed,
 *       and only on rows ABOUT the subject. Rows the subject merely AUTHORED
 *       keep their identity keys, because those name OTHER people: the member
 *       they assigned, the address they invited, the profile they renamed. The
 *       single exception is step 7c, which is matched on the value being the
 *       subject's own name, not on authorship.</li>
 *   <li>{@code course.instructor_name / _title / _bio} — independently authored
 *       editorial fields on the org's published catalogue, not a copy of the
 *       user row. The FK nulls {@code instructor_id}; blanking the rest breaks
 *       the course page for every enrolled learner. An instructor's erasure is
 *       an editorial action, not a self-service cascade.</li>
 *   <li>Orphan pseudonymous UUIDs with no FK to {@code users}:
 *       {@code exercise_templates.created_by}, {@code exercise_assignments.assigned_by},
 *       {@code exercise_comments.resolved_by}. Once the {@code users} row is gone
 *       these identify nobody, and nulling them would erase an org's authorship
 *       record for content the subject never owned.</li>
 *   <li>{@code leads}, {@code lead_magnet_requests} — marketing contacts captured
 *       through public forms under a separate purpose and lawful basis, with
 *       their own unsubscribe path and no link to any account. An ACCOUNT
 *       deletion does not reach them; erasure there is a separate request.</li>
 *   <li>{@code testimonials}, {@code business_cards} — platform marketing content
 *       with no user column at all.</li>
 *   <li>{@code invitations.email}, and {@code submissions} /
 *       {@code survey_responses} rows carrying only a {@code respondent_email} —
 *       unreachable without mailbox proof, see the identity note above.</li>
 * </ul>
 *
 * <p><strong>Known residuals</strong>, all accepted rather than fixed, because
 * every available fix costs more than the leak:
 * <ul>
 *   <li><em>Name-change window.</em> Step 6c matches the subject's CURRENT name.
 *       An {@code UPGRADE_REQUESTED} row stamped before a rename keeps the old
 *       one. Fixing it needs a name history nobody keeps.</li>
 *   <li><em>Namesake reach.</em> Step 6c also scrubs a row the subject authored
 *       about a different person with the byte-identical display name. Narrow,
 *       and it errs towards erasing rather than towards retaining a name.</li>
 *   <li><em>Orphaned {@code ASSESSMENT_ASSIGNED}.</em> Those rows carry
 *       {@code memberName} against a submission id; if the submission was
 *       deleted before the erasure, no id arm reaches them and only the name
 *       string links back. The fix belongs upstream — emit {@code memberId} in
 *       that audit row's details, after which arm 6b catches it forever.</li>
 * </ul>
 */
@Repository
@Slf4j
@RequiredArgsConstructor
public class PersonalDataRepository {

    /** Replaces the author/holder name on records that are anonymised rather than deleted. */
    public static final String ANONYMISED_NAME = "Deleted user";

    /** Identity keys removed from an {@code audit_logs} details blob. */
    private static final String STRIP_IDENTITY_KEYS = "- 'email' - 'memberName' - 'oldName' - 'newName'";

    /** The caller's own identity, used to confirm intent before an irreversible delete. */
    public record AccountIdentity(String email, String passwordHash) {}

    /**
     * Export sections, in report order. Every query binds exactly one named
     * parameter — {@code :userId} — so a caller cannot aim one at another user,
     * and the endpoints expose no id to tamper with.
     *
     * <p>Deliberately NOT exported: {@code refresh_tokens},
     * {@code password_reset_tokens} and {@code push_subscriptions} (credentials);
     * {@code insight_reports} (an AI narrative about the whole cohort — another
     * tenant's aggregate, not this person's record); {@code leads} /
     * {@code lead_magnet_requests} (see the retention list above).
     */
    private static final Map<String, String> EXPORT_SECTIONS = new LinkedHashMap<>();

    /**
     * The subject's rows, written as id sets so every child table can reuse them
     * verbatim with no alias juggling. Account-keyed only — see the class doc on
     * why email is not an identity here.
     */
    private static final String MY_SUBMISSIONS =
            "(SELECT id FROM submissions WHERE user_id = :userId)";

    private static final String MY_SURVEY_RESPONSES =
            "(SELECT id FROM survey_responses WHERE respondent_user_id = :userId)";

    private static final String MY_EXERCISE_SUBMISSIONS =
            "(SELECT id FROM exercise_submissions WHERE user_id = :userId)";

    static {
        EXPORT_SECTIONS.put("account", "SELECT * FROM users WHERE id = :userId");
        // Narrow on purpose: description/subscription_tier/trial_ends_at/is_active
        // are org-administrative fields OrganizationController gates to ORG_ADMIN+.
        // A MEMBER's Art. 15 copy names their controller, it does not hand them
        // their org's commercial record.
        EXPORT_SECTIONS.put("organization",
                "SELECT o.id, o.name FROM organizations o JOIN users u ON u.organization_id = o.id "
                        + "WHERE u.id = :userId");
        EXPORT_SECTIONS.put("assessment_assignments", "SELECT * FROM assignments WHERE user_id = :userId");
        EXPORT_SECTIONS.put("assessment_submissions", "SELECT * FROM submissions WHERE user_id = :userId");
        EXPORT_SECTIONS.put("assessment_answers",
                "SELECT * FROM answers WHERE submission_id IN " + MY_SUBMISSIONS);
        EXPORT_SECTIONS.put("pillar_evaluations",
                "SELECT * FROM pillar_evaluations WHERE submission_id IN " + MY_SUBMISSIONS);
        EXPORT_SECTIONS.put("overall_summaries",
                "SELECT * FROM overall_summaries WHERE submission_id IN " + MY_SUBMISSIONS);
        // Superseded profiling snapshots — still personal data, and the only place
        // an earlier score/narrative about the person survives a re-evaluation.
        EXPORT_SECTIONS.put("pillar_evaluation_history",
                "SELECT * FROM pillar_evaluation_history WHERE submission_id IN " + MY_SUBMISSIONS);
        EXPORT_SECTIONS.put("overall_summary_history",
                "SELECT * FROM overall_summary_history WHERE submission_id IN " + MY_SUBMISSIONS);
        // Profiling output about the user (Art. 15(1)(h)).
        EXPORT_SECTIONS.put("ai_use_detections",
                "SELECT * FROM submission_ai_detections WHERE submission_id IN " + MY_SUBMISSIONS);
        // Their prompt content only. system_prompt is our template, and raw_response
        // is the unparsed form of the evaluations already exported in full above —
        // neither adds anything about the person that is not already in the file.
        EXPORT_SECTIONS.put("ai_call_logs",
                "SELECT id, submission_id, call_type, model, pillar_name, called_at, status, user_message "
                        + "FROM ai_call_logs WHERE submission_id IN " + MY_SUBMISSIONS);

        EXPORT_SECTIONS.put("survey_responses",
                "SELECT * FROM survey_responses WHERE respondent_user_id = :userId");
        EXPORT_SECTIONS.put("survey_answers",
                "SELECT * FROM survey_answers WHERE response_id IN " + MY_SURVEY_RESPONSES);

        EXPORT_SECTIONS.put("course_enrollments", "SELECT * FROM enrollment WHERE user_id = :userId");
        // Automated decisions ABOUT the subject (Art. 15(1)(h)): which course they were
        // put on, which pillar band triggered it, and — when they were not enrolled —
        // why not. The enrolment rows above do not say any of that.
        EXPORT_SECTIONS.put("course_recommendations",
                "SELECT * FROM auto_enrolments WHERE user_id = :userId");
        // The other half of that decision: a course an ADMIN took them off, with the
        // reason. Exported for the same Art. 15(1)(h) argument as the row above — it
        // shapes what learning they are given and nothing else records it — and
        // separately because a founder asking "where did my course go" is entitled
        // to the answer. Erased by the FK CASCADE with the user row.
        EXPORT_SECTIONS.put("course_removals",
                "SELECT * FROM enrolment_overrides WHERE user_id = :userId");
        EXPORT_SECTIONS.put("course_progress",
                "SELECT * FROM content_progress WHERE enrollment_id IN "
                        + "(SELECT id FROM enrollment WHERE user_id = :userId)");
        EXPORT_SECTIONS.put("quiz_attempts", "SELECT * FROM quiz_attempt WHERE user_id = :userId");
        EXPORT_SECTIONS.put("quiz_attempt_answers",
                "SELECT * FROM quiz_attempt_answer WHERE attempt_id IN "
                        + "(SELECT id FROM quiz_attempt WHERE user_id = :userId)");
        EXPORT_SECTIONS.put("certificates", "SELECT * FROM certificate WHERE user_id = :userId");
        EXPORT_SECTIONS.put("course_reviews", "SELECT * FROM review WHERE user_id = :userId");

        EXPORT_SECTIONS.put("exercise_assignments", "SELECT * FROM exercise_assignments WHERE user_id = :userId");
        EXPORT_SECTIONS.put("exercise_submissions", "SELECT * FROM exercise_submissions WHERE user_id = :userId");
        // The member's actual worksheet content.
        EXPORT_SECTIONS.put("exercise_rows",
                "SELECT * FROM exercise_rows WHERE submission_id IN " + MY_EXERCISE_SUBMISSIONS);
        // cell_value_snapshot is excluded: on a comment left during review it freezes
        // ANOTHER member's answer verbatim. Art. 15(4) — a copy must not hand out
        // third parties' data. Same reasoning as the account_activity strip below.
        EXPORT_SECTIONS.put("exercise_comments", """
                SELECT id, submission_id, author_id, row_id, column_id, parent_id, body,
                       status, resolved_at, created_at, updated_at
                  FROM exercise_comments WHERE author_id = :userId
                """);

        EXPORT_SECTIONS.put("program_memberships", "SELECT * FROM program_module_members WHERE user_id = :userId");
        EXPORT_SECTIONS.put("program_submissions", "SELECT * FROM program_submissions WHERE user_id = :userId");

        EXPORT_SECTIONS.put("workshop_memberships", "SELECT * FROM workshop_team_members WHERE user_id = :userId");
        EXPORT_SECTIONS.put("workshop_submissions", "SELECT * FROM workshop_task_submissions WHERE user_id = :userId");

        EXPORT_SECTIONS.put("team_memberships", "SELECT * FROM team_members WHERE user_id = :userId");
        EXPORT_SECTIONS.put("cohort_memberships", "SELECT * FROM cohort_members WHERE user_id = :userId");

        // Coach grants ABOUT the subject: as the coach (their caseload) or as
        // the founder (who may see them). Erasure needs no statement here — the
        // subject's own rows CASCADE with the user row, and assigned_by is
        // SET NULL like every other admin attribution.
        EXPORT_SECTIONS.put("coach_assignments",
                "SELECT * FROM coach_assignments WHERE coach_id = :userId OR member_id = :userId");

        // Coach notes (V162): the coach's judgement ABOUT the member — exported
        // for both sides (data about the member; content the coach authored).
        // Erasure needs no statement: both FKs CASCADE with the user row.
        EXPORT_SECTIONS.put("coach_notes",
                "SELECT * FROM coach_notes WHERE coach_id = :userId OR member_id = :userId");

        // Session attendance (V163): presence marks ABOUT the member. Erasure
        // needs no statement: member_id CASCADEs with the users row and
        // marked_by is SET NULL like every other admin attribution.
        EXPORT_SECTIONS.put("session_attendance",
                "SELECT * FROM session_attendance WHERE member_id = :userId");

        // The subject's own coach profile (V153) — a link they published about
        // themselves. Erasure needs no statement: coach_id is the PK and
        // CASCADEs with the users row.
        EXPORT_SECTIONS.put("coach_profile",
                "SELECT * FROM coach_profiles WHERE coach_id = :userId");

        // Distance comparisons (V161): scores computed ABOUT the subject and
        // nobody else — user_id CASCADEs with the users row (pillar rows cascade
        // off the comparison), so erasure needs no statement. Exported with the
        // per-pillar snapshot rows: it is a stored evaluation outcome about them
        // that the raw pillar_evaluations sections above do not aggregate.
        EXPORT_SECTIONS.put("distance_comparisons",
                "SELECT * FROM founder_comparisons WHERE user_id = :userId");
        EXPORT_SECTIONS.put("distance_comparison_pillars", """
                SELECT * FROM founder_comparison_pillars
                 WHERE comparison_id IN (SELECT id FROM founder_comparisons WHERE user_id = :userId)
                """);

        // Shift narratives (V169, spec §6): AI-written prose ABOUT the subject,
        // reviewed and possibly edited by a human — squarely Art. 15 data about
        // them, and not derivable from anything else exported. user_id CASCADEs
        // with the users row, so erasure needs no statement; approved_by /
        // edited_by are SET NULL like every other staff attribution.
        EXPORT_SECTIONS.put("shift_narratives",
                "SELECT * FROM shift_narratives WHERE user_id = :userId");

        EXPORT_SECTIONS.put("notifications", "SELECT * FROM notifications WHERE user_id = :userId");
        EXPORT_SECTIONS.put("notification_optouts", "SELECT * FROM notification_optouts WHERE user_id = :userId");
        EXPORT_SECTIONS.put("upgrade_requests", "SELECT * FROM upgrade_requests WHERE requested_by = :userId");

        // The one section not using SELECT *: an ORG_ADMIN's own activity rows name
        // the members they acted on, and Art. 15(4) says a copy must not hand out
        // third parties' data. The identity keys are stripped, the trail is kept.
        EXPORT_SECTIONS.put("account_activity", """
                SELECT id, actor_id, action_type, entity_type, entity_id, organization_id, occurred_at,
                       details_json %s AS details_json
                  FROM audit_logs WHERE actor_id = :userId
                """.formatted(STRIP_IDENTITY_KEYS));
    }

    private final NamedParameterJdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    /** Every section for one user, empty sections included so the shape is stable. */
    public Map<String, List<Map<String, Object>>> exportFor(UUID userId) {
        var params = new MapSqlParameterSource("userId", userId);
        Map<String, List<Map<String, Object>>> out = new LinkedHashMap<>();
        EXPORT_SECTIONS.forEach((section, sql) ->
                out.put(section, jdbc.queryForList(sql, params).stream().map(this::scrubRow).toList()));
        return out;
    }

    /** {@code null} when no such user exists. */
    public AccountIdentity findAccount(UUID userId) {
        return jdbc.query("SELECT email, password_hash FROM users WHERE id = :userId",
                new MapSqlParameterSource("userId", userId),
                rs -> rs.next() ? new AccountIdentity(rs.getString(1), rs.getString(2)) : null);
    }

    /**
     * True when the org has exactly one active ORG_ADMIN — the caller. Sub-orgs
     * are governed by the parent's admins, so they legitimately have zero local
     * admins and are excluded (the count comes back 0, never 1).
     */
    public boolean isSoleActiveAdminOfTopLevelOrg(UUID orgId) {
        Integer admins = jdbc.queryForObject("""
                SELECT count(*) FROM users u
                 WHERE u.organization_id = :orgId
                   AND u.role = 'ORG_ADMIN'
                   AND u.status = 'ACTIVE'
                   AND EXISTS (SELECT 1 FROM organizations o
                                WHERE o.id = u.organization_id AND o.parent_organization_id IS NULL)
                """, new MapSqlParameterSource("orgId", orgId), Integer.class);
        return admins != null && admins == 1;
    }

    /**
     * Art. 17 erasure of everything the {@code users} row's own FK actions get
     * wrong. <strong>Call this immediately before deleting the users row</strong>
     * — several statements read {@code users} or the subject's submissions, and
     * step 1 is not optional: without it the delete aborts.
     *
     * <p>Every statement is keyed on {@code users.id} or on a value only the
     * subject's own row can supply. Some of them reach across organisations —
     * an id is global — but none of them can be aimed by choosing an input, so
     * both callers run exactly the same set.
     *
     * @param userId the subject
     */
    public void erasePersonalDataFor(UUID userId) {
        var p = new MapSqlParameterSource()
                .addValue("userId", userId)
                .addValue("userIdText", userId.toString());

        // 1. Their survey responses. The FK is ON DELETE SET NULL, but
        //    ck_post_assessment_has_user_and_submission / ck_workshop_intro_has_user_and_workshop
        //    require a non-null respondent, so SET NULL would abort the whole delete
        //    with a check violation. Their own answers, so: delete.
        int surveyResponses = jdbc.update(
                "DELETE FROM survey_responses WHERE respondent_user_id = :userId", p);

        // 2. AI call logs. No FK — correlated to submissions by a plain UUID — so
        //    the cascade never reaches them, and user_message holds the subject's
        //    answers verbatim. Runs before the submissions cascade takes the ids away.
        int aiCallLogs = jdbc.update(
                "DELETE FROM ai_call_logs WHERE submission_id IN "
                        + "(SELECT id FROM submissions WHERE user_id = :userId)", p);

        // 3. Certificates: anonymise, do NOT delete. V85 deliberately made this FK
        //    SET NULL so the row survives its holder and the public
        //    verify-by-certificate-number lookup keeps working — deleting would 404
        //    every number the person ever shared with an employer or investor.
        //    Cutting user_id and the learner name satisfies Art. 17 and the public
        //    contract at once.
        int certificates = jdbc.update(
                "UPDATE certificate SET user_id = NULL, learner_name = :anon WHERE user_id = :userId",
                p.addValue("anon", ANONYMISED_NAME));

        // 4. Course reviews: anonymise, do NOT delete. course.rating / reviews_count
        //    are aggregates other learners and the org depend on; dropping rows would
        //    silently rewrite another tenant's published catalogue.
        int reviews = jdbc.update(
                "UPDATE review SET user_id = NULL, author_name = :anon WHERE user_id = :userId", p);

        // 5. Exercise comment threads. author_id CASCADEs and parent_id CASCADEs with
        //    it, so deleting a reviewer would delete their comments on OTHER members'
        //    submissions AND drag down those members' replies. Detach the replies
        //    first: the third parties' words survive as roots, the subject's own
        //    comments still go with them (which is what Art. 17 asks for — there is
        //    no author-name column to anonymise, and author_id is NOT NULL).
        int reparented = jdbc.update("""
                UPDATE exercise_comments SET parent_id = NULL
                 WHERE author_id <> :userId
                   AND parent_id IN (SELECT id FROM exercise_comments WHERE author_id = :userId)
                """, p);

        // 6a. Audit rows ABOUT the subject: keep the row, drop the identity. The FK
        //     nulls actor_id, but the details blob still names them in plain text.
        //     Indexed by idx_audit_logs_entity (entity_type, entity_id).
        //     Rows the subject AUTHORED are deliberately untouched — their details
        //     name OTHER people (the member assigned, the invitee, the member whose
        //     profile was edited), and erasing those is someone else's erasure.
        int auditAboutSubject = jdbc.update("""
                UPDATE audit_logs SET details_json = details_json %s
                 WHERE entity_type = 'User' AND entity_id = :userId
                   AND jsonb_exists_any(details_json, array['email', 'memberName', 'oldName', 'newName'])
                """.formatted(STRIP_IDENTITY_KEYS), p);

        // 6b. The arms no index can serve, merged into one scan: rows naming the
        //     subject by id inside the blob, and rows attached to their submissions
        //     (ASSESSMENT_ASSIGNED carries memberName against a submission id).
        //
        //     There is deliberately no arm matching the subject's email VALUE. It
        //     would be the only predicate here aimable by choosing an input, and
        //     users.email is unverified — see the identity note on this class. The
        //     rows it would have reached are named in that note as needing mailbox
        //     proof.
        int auditNamingSubject = jdbc.update("""
                UPDATE audit_logs SET details_json = details_json %s
                 WHERE (details_json->>'memberId' = :userIdText
                        OR entity_id IN (SELECT id FROM submissions WHERE user_id = :userId))
                   AND jsonb_exists_any(details_json, array['email', 'memberName', 'oldName', 'newName'])
                """.formatted(STRIP_IDENTITY_KEYS), p);

        // 6c. The one gap 6a/6b leave: a row the subject authored ABOUT THEMSELVES
        //     (UPGRADE_REQUESTED stamps memberName = the requester, against the org,
        //     with no id anywhere in the blob). Scoped by actor AND by the value
        //     literally being their own name, so it can never reach a third party
        //     the way a blanket actor_id arm would. Residual: an exact namesake the
        //     subject once acted on would be scrubbed too — narrow, and erring
        //     towards erasure rather than towards retaining an identified person.
        int auditSelfAuthored = jdbc.update("""
                UPDATE audit_logs SET details_json = details_json - 'memberName'
                 WHERE actor_id = :userId
                   AND details_json->>'memberName' = (SELECT name FROM users WHERE id = :userId)
                """, p);

        log.info("GDPR erasure prep for user {}: surveyResponses={} aiCallLogs={} "
                        + "certificatesAnonymised={} reviewsAnonymised={} repliesDetached={} "
                        + "auditAbout={} auditNaming={} auditSelfAuthored={}",
                userId, surveyResponses, aiCallLogs, certificates, reviews,
                reparented, auditAboutSubject, auditNamingSubject, auditSelfAuthored);
    }

    /**
     * Deletes the {@code users} row itself, for callers with no JPA entity in
     * hand. {@code MemberService} deletes through {@code UserRepository} instead
     * so the principal-cache eviction listener fires.
     */
    public boolean deleteUserRow(UUID userId) {
        return jdbc.update("DELETE FROM users WHERE id = :userId",
                new MapSqlParameterSource("userId", userId)) > 0;
    }

    private Map<String, Object> scrubRow(Map<String, Object> row) {
        Map<String, Object> out = new LinkedHashMap<>();
        row.forEach((column, value) -> {
            if (!isSecret(column)) {
                out.put(column, normalise(value));
            }
        });
        return out;
    }

    /**
     * Credentials and capability tokens, matched by SHAPE rather than an exact
     * name list — an exact list silently exported {@code survey_responses.gift_token}
     * because it was not spelled {@code token}. Handing these back in a file the
     * user may email around is a security hole, not a portability win.
     */
    private static boolean isSecret(String column) {
        return column.equals("token") || column.equals("auth") || column.equals("p256dh")
                || column.endsWith("_token") || column.endsWith("_hash")
                || column.endsWith("_key") || column.contains("secret");
    }

    /**
     * JDBC hands back driver-specific types (notably {@code PGobject} for
     * {@code jsonb}) that Jackson would render as {@code {"type":…,"value":…}}.
     * Map them to plain JSON values, re-parsing jsonb so the export stays nested
     * instead of becoming a string inside a string.
     */
    private Object normalise(Object value) {
        return switch (value) {
            case null -> null;
            case String s -> s;
            case Number n -> n;
            case Boolean b -> b;
            case Timestamp ts -> ts.toInstant().toString();
            case UUID id -> id.toString();
            default -> asJsonOrText(String.valueOf(value));
        };
    }

    private Object asJsonOrText(String text) {
        if (text.startsWith("{") || text.startsWith("[")) {
            try {
                return objectMapper.readTree(text);
            } catch (Exception e) {
                return text; // not JSON after all — hand back the text form
            }
        }
        return text;
    }
}
