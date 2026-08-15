package com.bvisionry.communication.web;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.owasp.html.HtmlPolicyBuilder;
import org.owasp.html.PolicyFactory;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.util.HtmlUtils;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.coachaccess.CoachAccess;
import com.bvisionry.common.event.CommunicationEvents;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.communication.domain.Announcement;
import com.bvisionry.communication.dto.AnnouncementCohortResponse;
import com.bvisionry.communication.dto.AnnouncementResponse;
import com.bvisionry.communication.dto.CreateAnnouncementRequest;
import com.bvisionry.communication.dto.MyAnnouncementResponse;
import com.bvisionry.communication.repository.AnnouncementReadRepository;
import com.bvisionry.communication.repository.AnnouncementRepository;

import com.bvisionry.common.programaccess.OrgCohortAccess;
import lombok.RequiredArgsConstructor;

/**
 * Cohort broadcasts (roadmap §7 item 20). One-way only — policy
 * {@code decisions.communications: ANNOUNCEMENTS_ONLY} — so there is nothing
 * here but post, read and report.
 *
 * <p><strong>Authorization is settled in the data layer, not just above it.</strong>
 * The controller's {@code @orgAccess.isInOrg(#orgId)} pins the caller to the
 * path org; on top of that every cohort is re-resolved WITH that org (a foreign
 * cohort id reads as absent, never as reachable) and a COACH additionally has
 * to hold a whole-cohort grant on it, checked through the shared
 * {@link CoachAccess} kernel. A coach broadcasting to an unassigned cohort is a
 * 404 even if the HTTP and method layers were misconfigured.
 *
 * <p><strong>Delivery reuses the existing notification pipeline.</strong> The
 * post publishes a {@code common} event; the {@code notification} slice's push
 * handler fans it out through the same preference-respecting dispatch every
 * other notification type uses. There is no second delivery path and therefore
 * no second place where an opt-out could be forgotten.
 */
@Service
@RequiredArgsConstructor
public class AnnouncementService {

    /**
     * Deny-all OWASP policy: keeps text, drops every tag (and the CONTENT of
     * script/style). The same sanitiser library the email renderer uses — the
     * difference is only the policy, because an announcement body is plain text
     * (policy {@code announcement_body: PLAIN_TEXT_PLUS_LINKS}), not rich text.
     * Used to RECOGNISE plain text, not to produce it — see {@link #sanitize}.
     */
    private static final PolicyFactory STRIP_MARKUP = new HtmlPolicyBuilder().toFactory();

    private final AnnouncementRepository announcements;
    private final AnnouncementReadRepository reads;
    private final OrgCohortAccess orgCohorts;
    private final CoachAccess coachAccess;
    private final CurrentUserAccessor currentUser;
    private final AuditLogger auditLogger;
    private final ApplicationEventPublisher events;

    /* --------------------------------------------------------------- reads */

    /** The cohorts the caller may broadcast to — all of the org's, or a coach's grants. */
    @Transactional(readOnly = true)
    public List<AnnouncementCohortResponse> broadcastTargets(UUID orgId) {
        CurrentUser caller = currentUser.require();
        List<AnnouncementReadRepository.CohortRow> rows = isCoach(caller)
                ? reads.cohortsGrantedToCoach(orgId, caller.userId())
                : reads.cohortsInOrg(orgId);
        return rows.stream()
                .map(row -> new AnnouncementCohortResponse(row.id(), row.name()))
                .toList();
    }

    /**
     * One cohort's posts, newest first. Same authorization as posting to it —
     * with one difference in what comes BACK: the {@code flagged} moderation
     * signal is for the org admins who can act on a report, not for the coach a
     * report may be about, so a coach's read serializes {@code flagged: false}.
     */
    @Transactional(readOnly = true)
    public List<AnnouncementResponse> feed(UUID orgId, UUID cohortId) {
        CurrentUser caller = currentUser.require();
        requireBroadcastableCohort(orgId, cohortId, caller);
        boolean moderator = !isCoach(caller);
        return reads.feed(orgId, cohortId).stream()
                .map(row -> AnnouncementResponse.from(row, moderator))
                .toList();
    }

    /**
     * The announcements the CALLER received: posts to cohorts they belong to,
     * newest first. Identity is the scope — a caller with no org has received
     * nothing, which is an empty list, not an error.
     */
    @Transactional(readOnly = true)
    public List<MyAnnouncementResponse> myFeed() {
        CurrentUser caller = currentUser.require();
        if (caller.orgId() == null) {
            return List.of();
        }
        return reads.memberFeed(caller.orgId(), caller.userId()).stream()
                .map(MyAnnouncementResponse::from)
                .toList();
    }

    /* --------------------------------------------------------------- write */

    @Transactional
    public AnnouncementResponse post(UUID orgId, UUID cohortId, CreateAnnouncementRequest request) {
        CurrentUser caller = currentUser.require();
        String cohortName = requireBroadcastableCohort(orgId, cohortId, caller);

        // REJECT, don't rewrite. Sanitising and storing the result is not a
        // fixpoint: the sanitiser decodes entities, so `&lt;script&gt;` comes
        // back out as literal `<script>` — a second pass would differ again,
        // and the "stored body is plain text" invariant every reader relies on
        // would be false. Demanding the body already BE its canonical form
        // closes that: ordinary text ("Demo day & drinks < 5pm") is its own
        // canonical form and passes; anything carrying markup or HTML codes,
        // encoded once or twice, is refused where the author can fix it.
        String body = request.body() == null ? "" : request.body().trim();
        if (body.isBlank() || !isCanonicalPlainText(body)) {
            throw new BadRequestException(
                    "Announcements are plain text — remove any markup or HTML codes and try again.");
        }

        Announcement announcement = new Announcement();
        announcement.setOrgId(orgId);
        announcement.setCohortId(cohortId);
        announcement.setAuthorId(caller.userId());
        announcement.setBody(body);
        Announcement saved = announcements.save(announcement);

        // Recipients are resolved at SEND time: whoever is enrolled now.
        List<UUID> recipients = reads.recipientIds(orgId, cohortId, caller.userId());

        auditLogger.log(caller.userId(), orgId, "ANNOUNCEMENT_POSTED", "Announcement",
                saved.getId(), Map.of(
                        "cohortId", cohortId.toString(),
                        "cohortName", cohortName,
                        "recipients", recipients.size()));

        events.publishEvent(new CommunicationEvents.AnnouncementPosted(
                saved.getId(), cohortId, cohortName, caller.name(), body, recipients));

        return new AnnouncementResponse(saved.getId(), cohortId, cohortName, caller.name(),
                body, false, saved.getCreatedAt());
    }

    /**
     * A recipient flags a post. Minimal by design: the first report flips a
     * flag an org admin can see on the cohort feed and writes one audit row.
     * No queue, no states, no takedown. Repeat reports are a no-op.
     */
    @Transactional
    public void report(UUID announcementId) {
        CurrentUser caller = currentUser.require();
        Announcement announcement = requireReportableAnnouncement(announcementId, caller.userId());

        // First report only: flag AND audit together. Auditing every repeat
        // would let one recipient write unbounded audit rows, and a report
        // count is not something any surface asks for.
        if (announcement.getFlaggedAt() == null) {
            announcement.setFlaggedAt(Instant.now());
            announcement.setFlaggedBy(caller.userId());
            announcements.save(announcement);
            auditLogger.log(caller.userId(), announcement.getOrgId(), "ANNOUNCEMENT_REPORTED",
                    "Announcement", announcement.getId(),
                    Map.of("cohortId", announcement.getCohortId().toString()));
        }
    }

    /* -------------------------------------------------------------- guards */

    /**
     * The cohort, resolved for THIS caller: it must belong to the path org, and
     * a coach must hold a whole-cohort grant on it. Anything else is absent.
     *
     * @return the cohort's display name
     */
    private String requireBroadcastableCohort(UUID orgId, UUID cohortId, CurrentUser caller) {
        String name = orgCohorts.cohortNameInOrg(orgId, cohortId)
                .orElseThrow(() -> new ResourceNotFoundException("Cohort", cohortId.toString()));
        if (isCoach(caller) && !coachAccess.coachHoldsCohort(orgId, caller.userId(), cohortId)) {
            // 404, not 403: a coach may not learn which cohort ids exist outside
            // their grants — the same idiom as the coach console's founder 404.
            throw new ResourceNotFoundException("Cohort", cohortId.toString());
        }
        return name;
    }

    /** Only someone the broadcast actually reached may report it. */
    private Announcement requireReportableAnnouncement(UUID announcementId, UUID reporterId) {
        Announcement announcement = announcements.findById(announcementId)
                .orElseThrow(() -> new ResourceNotFoundException("Announcement",
                        announcementId.toString()));
        if (!reads.isCohortMember(announcement.getOrgId(), announcement.getCohortId(), reporterId)) {
            throw new ResourceNotFoundException("Announcement", announcementId.toString());
        }
        return announcement;
    }

    private static boolean isCoach(CurrentUser caller) {
        return "COACH".equals(caller.role());
    }

    /* ----------------------------------------------------------- sanitiser */

    /**
     * Is {@code body} already its own canonical plain text — i.e. does the
     * deny-all OWASP policy (which drops every tag and the CONTENT of
     * script/style) plus entity-unescaping leave it unchanged?
     *
     * <p><strong>Returns a boolean, not the canonical string, on purpose.</strong>
     * The sanitiser's OUTPUT must never be stored: it decodes entities, so
     * {@code &lt;script&gt;} canonicalises to a literal {@code <script>}, which
     * canonicalises again to something else. One pass would have written live
     * markup into a column every reader treats as text, and entity-encoding
     * would have walked straight past the blank-body rule. A caller that cannot
     * obtain the string cannot store it — the only safe use of this sanitiser
     * in this slice is the question below, so the question is all it exposes.
     *
     * <p>{@code body} must be non-null and non-blank; {@link #post} short-circuits
     * on both before asking.
     */
    static boolean isCanonicalPlainText(String body) {
        return HtmlUtils.htmlUnescape(STRIP_MARKUP.sanitize(body)).trim().equals(body);
    }
}
