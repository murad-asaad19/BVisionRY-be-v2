package com.bvisionry.quiz.web;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.quiz.dto.QuizOptionTakingDto;
import com.bvisionry.quiz.dto.QuizQuestionTakingDto;
import com.bvisionry.quiz.dto.QuizTakingDto;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.bvisionry.testsupport.TestAuthentication;

import jakarta.persistence.EntityManager;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@code GET /api/v1/courses/{slug}/content/{contentId}/quiz} against a REAL
 * quiz graph. {@link QuizServiceTest} mocks the repository, so the query itself
 * is never translated there and this class is the only place the fetch plan is
 * exercised.
 *
 * <p>THE BUG THIS EXISTS FOR: {@code Quiz.questions} and
 * {@code QuizQuestion.options} are both Lists — Hibernate bags — and the taking
 * query join-fetched BOTH in one statement. Hibernate refuses that outright with
 * {@code MultipleBagFetchException: cannot simultaneously fetch multiple bags},
 * so every quiz-taking request 500'd the moment a course was launched with a
 * quiz on it. The defect is latent until a quiz exists, which is why nothing
 * caught it until the first e2e run against real content.
 *
 * <p>The fixture needs TWO questions with TWO options each on purpose: one
 * question, or one option, still exercises the same illegal fetch plan, but only
 * a real many-to-many shape proves the two-query split also stitches the right
 * options onto the right question and preserves {@code sequence} ordering.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
@Transactional
class QuizTakingFetchIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String SLUG = "quiz-fetch-course";

    @Autowired private EntityManager em;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private QuizService quizService;
    @Autowired private UserRepository userRepository;
    @Autowired private OrganizationRepository organizationRepository;

    private UUID contentId;

    @BeforeEach
    void seed() {
        Organization org = new Organization();
        org.setName("Quiz Fetch Org " + UUID.randomUUID());
        org.setActive(true);
        org = organizationRepository.save(org);

        User learner = TestAuthentication.authenticateAsMember(userRepository, org);
        // The rest of the graph is seeded with raw SQL, which does not see rows
        // still sitting in the persistence context — flush the two JPA saves.
        em.flush();

        UUID orgId = org.getId();
        UUID courseId = UUID.randomUUID();
        UUID sectionId = UUID.randomUUID();
        contentId = UUID.randomUUID();
        UUID quizId = UUID.randomUUID();

        jdbc.update("INSERT INTO course (id, org_id, slug, title, state) VALUES (?, ?, ?, 'Quiz Course', 'PUBLISHED')",
                courseId, orgId, SLUG);
        jdbc.update("INSERT INTO section (id, org_id, course_id, title) VALUES (?, ?, ?, 'Module 1')",
                sectionId, orgId, courseId);
        jdbc.update("""
                INSERT INTO content (id, org_id, section_id, title, content_type)
                VALUES (?, ?, ?, 'The Quiz', 'QUIZ')
                """, contentId, orgId, sectionId);
        jdbc.update("INSERT INTO enrollment (user_id, course_id, status) VALUES (?, ?, 'ACTIVE')",
                learner.getId(), courseId);

        jdbc.update("INSERT INTO quiz (id, content_id, passing_score_pct, max_attempts) VALUES (?, ?, 70, 0)",
                quizId, contentId);
        // Inserted out of sequence order so the assertions below prove ORDERING,
        // not merely insertion order coming back unchanged.
        question(quizId, 1, "Second question", "Q2 wrong", "Q2 right");
        question(quizId, 0, "First question", "Q1 wrong", "Q1 right");
    }

    @AfterEach
    void clearAuth() {
        TestAuthentication.clear();
    }

    @Test
    void loadsEveryQuestionWithItsOwnOptionsInSequenceOrder() {
        QuizTakingDto quiz = quizService.getForTaking(SLUG, contentId);

        assertThat(quiz.questions())
                .extracting(QuizQuestionTakingDto::prompt)
                .containsExactly("First question", "Second question");

        assertThat(quiz.questions())
                .allSatisfy(q -> assertThat(q.options()).hasSize(2));

        // Stitching: each question must carry ITS OWN options, not the other's.
        assertThat(quiz.questions().get(0).options())
                .extracting(QuizOptionTakingDto::text)
                .containsExactly("Q1 wrong", "Q1 right");
        assertThat(quiz.questions().get(1).options())
                .extracting(QuizOptionTakingDto::text)
                .containsExactly("Q2 wrong", "Q2 right");
    }

    private void question(UUID quizId, int sequence, String prompt, String wrong, String right) {
        UUID questionId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO quiz_question (id, quiz_id, type, prompt, points, sequence)
                VALUES (?, ?, 'SINGLE_CHOICE', ?, 1, ?)
                """, questionId, quizId, prompt, sequence);
        jdbc.update("INSERT INTO quiz_option (question_id, text, is_correct, sequence) VALUES (?, ?, false, 0)",
                questionId, wrong);
        jdbc.update("INSERT INTO quiz_option (question_id, text, is_correct, sequence) VALUES (?, ?, true, 1)",
                questionId, right);
    }
}
