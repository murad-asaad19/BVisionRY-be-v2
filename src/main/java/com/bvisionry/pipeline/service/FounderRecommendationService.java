package com.bvisionry.pipeline.service;

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

        List<AutoEnrolment> decisions = ledger.findByUserIdAndOutcomeOrderByCreatedAtDescIdAsc(
                founderId, AutoEnrolmentOutcome.ENROLLED);
        if (decisions.isEmpty()) {
            return List.of();
        }

        Set<UUID> courseIds = decisions.stream()
                .map(AutoEnrolment::getCourseId).collect(Collectors.toSet());
        Map<UUID, EnrolledCourse> courses = courseCatalog.findEnrolledByFounder(founderId, courseIds);

        Set<UUID> pillarIds = decisions.stream()
                .map(AutoEnrolment::getPillarId).collect(Collectors.toSet());
        Map<UUID, String> pillarNames = pillarRepository.findAllById(pillarIds).stream()
                .collect(Collectors.toMap(Pillar::getId, Pillar::getName));

        Map<UUID, RecommendedCourseResponse> newestPerCourse = new LinkedHashMap<>();
        for (AutoEnrolment decision : decisions) {
            EnrolledCourse course = courses.get(decision.getCourseId());
            String pillarName = pillarNames.get(decision.getPillarId());
            if (course == null || pillarName == null) {
                continue;
            }
            newestPerCourse.putIfAbsent(course.id(), new RecommendedCourseResponse(
                    course.id(), course.title(), course.slug(), course.published(),
                    pillarName, decision.getSubmissionId()));
        }
        return List.copyOf(newestPerCourse.values());
    }
}
