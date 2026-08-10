package com.bvisionry.pipeline.service;

import com.bvisionry.common.coursevisibility.CourseVisibilityAccess;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.pipeline.dto.RecommendedCourseResponse;
import com.bvisionry.pipeline.entity.AutoEnrolment;
import com.bvisionry.pipeline.entity.AutoEnrolmentOutcome;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.repository.AutoEnrolmentRepository;
import com.bvisionry.pipeline.repository.CourseCatalogReadRepository;
import com.bvisionry.pipeline.repository.CourseCatalogReadRepository.EnrolledCourse;
import com.bvisionry.pipeline.repository.PillarRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import java.util.stream.Stream;

/**
 * The founder's own view of what their assessments put them on:
 * "recommended because of &lt;pillar&gt;".
 *
 * <p>The write side ({@link AutoEnrolmentService}) has no endpoint at all — its
 * only entry point is an in-process event. This is the read, and it brings the
 * three layers that surface needs:
 * <ol>
 *   <li><strong>Route</strong> — {@code /api/my/recommendations} sits under
 *       {@code SecurityConfig}'s {@code anyRequest().authenticated()} floor, the
 *       same one every other {@code /api/my/**} founder surface stands on. It is
 *       deliberately NOT under {@code /api/pipelines/**}, whose
 *       {@code /*}/{@code pillars/**} subtree is floored at SUPER_ADMIN — a
 *       founder-readable route there would either be locked out or would force
 *       widening a rule that exists to protect the instrument's definition.</li>
 *   <li><strong>Method</strong> — {@code @PreAuthorize("isAuthenticated()")} on
 *       {@code MyRecommendationController}.</li>
 *   <li><strong>Data</strong> — the founder id comes from
 *       {@link CurrentUserAccessor}, never from a path or a body, and it constrains
 *       BOTH reads: the ledger by {@code user_id}, and the catalog by an
 *       {@code enrollment} join. There is no argument a caller can pass that makes
 *       this return someone else's row.</li>
 * </ol>
 *
 * <p>ponytail: three small reads, no join view and no cache. A founder has a
 * handful of ledger rows, so this is one indexed scan plus two batched
 * {@code IN} lookups per request.
 */
@Service
@RequiredArgsConstructor
public class FounderRecommendationService {

    private final AutoEnrolmentRepository ledger;
    private final CourseCatalogReadRepository courseCatalog;
    private final PillarRepository pillarRepository;
    private final CourseVisibilityAccess courseVisibility;
    private final CurrentUserAccessor currentUser;

    /**
     * Every course the engine actually enrolled the caller in, newest first, each
     * with the pillar that asked for it.
     *
     * <p>A row is dropped rather than degraded when either half of the sentence is
     * missing — no course row means the founder is no longer enrolled (the ledger
     * records what WAS decided, the enrolment is what they HAVE), and no pillar row
     * means the reason cannot be stated. "Recommended" with a blank because-clause
     * is the one thing this surface must never render.
     *
     * <p>De-duplicated by course, keeping the newest decision: a founder unenrolled
     * from a course and re-recommended it by a later assessment has two
     * {@code ENROLLED} rows, and two identical cards would read as a bug rather than
     * as history.
     */
    @Transactional(readOnly = true)
    public List<RecommendedCourseResponse> myRecommendations() {
        UUID founderId = currentUser.require().userId();

        // Open suggestions first: they are the only rows with an ACTION attached
        // (one-tap Accept), so they lead the rail rather than sitting under
        // courses the founder already has.
        List<AutoEnrolment> allSuggested = ledger.findByUserIdAndOutcomeOrderByCreatedAtDescIdAsc(
                founderId, AutoEnrolmentOutcome.SUGGESTED);
        // An ACCEPTED suggestion keeps its ledger row and its reason; it simply
        // stops offering the Accept button. Dropping it would make the card
        // vanish the instant a member tapped it — the one moment the rail should
        // be confirming what just happened.
        List<AutoEnrolment> suggestions = allSuggested.stream()
                .filter(d -> d.getAcceptedAt() == null).toList();
        List<AutoEnrolment> enrolled = new java.util.ArrayList<>(
                allSuggested.stream().filter(d -> d.getAcceptedAt() != null).toList());
        enrolled.addAll(ledger.findByUserIdAndOutcomeOrderByCreatedAtDescIdAsc(
                founderId, AutoEnrolmentOutcome.ENROLLED));
        if (suggestions.isEmpty() && enrolled.isEmpty()) {
            return List.of();
        }

        // Two course reads because the two halves ask different questions: an
        // ENROLLED row is resolved through the enrolment (any state — the founder
        // has it), a SUGGESTED row through the catalog (PUBLISHED only — they do
        // not).
        Map<UUID, EnrolledCourse> courses = new LinkedHashMap<>(
                courseCatalog.findEnrolledByFounder(founderId, ids(enrolled)));
        Set<UUID> suggestedIds = ids(suggestions);
        Map<UUID, EnrolledCourse> suggestedCourses = courseCatalog.findPublishedByIds(suggestedIds);
        // Spec §3: a rule may never surface a course the org cannot see. The
        // engine already skips them at decision time; this re-check covers a
        // suggestion made BEFORE the platform pulled the course.
        Set<UUID> visible = courseVisibility.filterVisibleForUser(founderId, suggestedIds);
        suggestedCourses.forEach((id, course) -> {
            if (visible.contains(id)) {
                courses.putIfAbsent(id, course);
            }
        });

        Set<UUID> pillarIds = Stream.concat(suggestions.stream(), enrolled.stream())
                .map(AutoEnrolment::getPillarId).collect(Collectors.toSet());
        Map<UUID, String> pillarNames = pillarRepository.findAllById(pillarIds).stream()
                .collect(Collectors.toMap(Pillar::getId, Pillar::getName));

        Map<UUID, RecommendedCourseResponse> newestPerCourse = new LinkedHashMap<>();
        collect(newestPerCourse, suggestions, courses, pillarNames, true);
        collect(newestPerCourse, enrolled, courses, pillarNames, false);
        return List.copyOf(newestPerCourse.values());
    }

    private static void collect(Map<UUID, RecommendedCourseResponse> out, List<AutoEnrolment> decisions,
                                Map<UUID, EnrolledCourse> courses, Map<UUID, String> pillarNames,
                                boolean suggested) {
        for (AutoEnrolment decision : decisions) {
            EnrolledCourse course = courses.get(decision.getCourseId());
            String pillarName = pillarNames.get(decision.getPillarId());
            if (course == null || pillarName == null) {
                continue;
            }
            out.putIfAbsent(course.id(), new RecommendedCourseResponse(
                    course.id(), course.title(), course.slug(), course.published(),
                    pillarName, decision.getSubmissionId(), suggested));
        }
    }

    private static Set<UUID> ids(List<AutoEnrolment> decisions) {
        return decisions.stream().map(AutoEnrolment::getCourseId).collect(Collectors.toSet());
    }
}
