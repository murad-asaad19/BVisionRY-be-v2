package com.bvisionry.reporting.service;

import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.assessment.entity.Assignment;
import com.bvisionry.assessment.entity.Submission;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.evaluation.OverallSummaryRepository;
import com.bvisionry.evaluation.PillarEvaluationRepository;
import com.bvisionry.evaluation.entity.OverallSummary;
import com.bvisionry.evaluation.entity.PillarEvaluation;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.service.PostCompletionLinkResolver;
import com.bvisionry.reporting.dto.MemberHistoryResponse;
import com.bvisionry.reporting.dto.MemberResultsResponse;
import com.bvisionry.reporting.dto.PillarDetailResponse;
import com.bvisionry.survey.repository.SurveyResponseRepository;
import com.bvisionry.survey.service.SurveyResultsService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.util.ReflectionTestUtils;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class MemberResultsServiceTest {

    @Mock
    private SubmissionRepository submissionRepository;

    @Mock
    private PillarEvaluationRepository pillarEvaluationRepository;

    @Mock
    private OverallSummaryRepository overallSummaryRepository;

    @Mock
    private PremiumFeatureGuard premiumFeatureGuard;

    @Mock
    private PostCompletionLinkResolver postCompletionLinkResolver;

    @Mock
    private SurveyResponseRepository surveyResponseRepository;

    @Mock
    private SurveyResultsService surveyResultsService;

    @Mock
    private PersonalInfoResolver personalInfoResolver;

    @InjectMocks
    private MemberResultsService memberResultsService;

    @BeforeEach
    void wireSelfReference() {
        // In production, Spring injects a proxy into the `self` field so that
        // calls to self.getCachedResults() go through the @Cacheable interceptor.
        // In unit tests we bypass the cache layer and call the same instance
        // directly -- caching behavior is covered separately by integration tests.
        ReflectionTestUtils.setField(memberResultsService, "self", memberResultsService);
    }

    @BeforeEach
    void seedMemberPrincipal() {
        // Default principal for tests: a non-super-admin member. Survey
        // payloads are visible to super admins only -- individual tests that
        // need to assert the super-admin path override this in-test.
        authenticateAs(UserRole.MEMBER);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    private void authenticateAs(UserRole role) {
        User caller = new User();
        caller.setId(UUID.randomUUID());
        caller.setRole(role);
        SecurityContextHolder.getContext().setAuthentication(
                new UsernamePasswordAuthenticationToken(caller, null, List.of()));
    }

    @Test
    void getResults_evaluatedSubmission_returnsPillarScores() {
        UUID submissionId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        Organization org = new Organization();
        org.setId(orgId);
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);

        Pipeline pipeline = new Pipeline();
        pipeline.setName("Leadership Assessment");

        Assignment assignment = new Assignment();
        assignment.setPipeline(pipeline);
        assignment.setOrganization(org);

        User user = new User();
        user.setId(UUID.randomUUID());

        Submission submission = new Submission();
        submission.setId(submissionId);
        submission.setAssignment(assignment);
        submission.setUser(user);
        submission.setStatus(SubmissionStatus.EVALUATED);
        submission.setEvaluatedAt(Instant.now());

        Pillar pillar = new Pillar();
        pillar.setId(UUID.randomUUID());
        pillar.setName("Communication");
        pillar.setIconKey("chat");

        PillarEvaluation eval = new PillarEvaluation();
        eval.setPillar(pillar);
        eval.setScorePercentage(new BigDecimal("78.50"));
        eval.setMaturityLabel("Strong");
        eval.setAiScoreMeans("Good communication skills");
        eval.setAiWhatsWorking(List.of("Active listening"));
        eval.setAiWhatCanImprove(List.of("Public speaking"));
        eval.setAiBusinessRelevance("Critical for team leadership");

        OverallSummary summary = new OverallSummary();
        summary.setOverallScorePercentage(new BigDecimal("75.00"));
        summary.setSummaryNarrative("Strong overall performance");
        summary.setStrengths(List.of("Leadership"));
        summary.setDevelopmentAreas(List.of("Time management"));
        summary.setRecommendations(List.of("Practice delegation"));

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(pillarEvaluationRepository.findBySubmissionId(submissionId)).thenReturn(List.of(eval));
        when(overallSummaryRepository.findBySubmissionId(submissionId)).thenReturn(Optional.of(summary));
        when(premiumFeatureGuard.isPremiumOrSuperAdmin(orgId)).thenReturn(true);

        MemberResultsResponse response = memberResultsService.getResults(submissionId);

        assertThat(response.submissionId()).isEqualTo(submissionId);
        assertThat(response.pipelineName()).isEqualTo("Leadership Assessment");
        assertThat(response.overallScore()).isEqualByComparingTo(new BigDecimal("75.00"));
        assertThat(response.pillarScores()).hasSize(1);
        assertThat(response.pillarScores().getFirst().pillarName()).isEqualTo("Communication");
        assertThat(response.premiumFeaturesAvailable()).isTrue();
    }

    @Test
    void getResults_freeOrg_hidesAiFeedback() {
        UUID submissionId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();

        Organization org = new Organization();
        org.setId(orgId);
        org.setSubscriptionTier(SubscriptionTier.FREE);

        Pipeline pipeline = new Pipeline();
        pipeline.setName("Test Assessment");

        Assignment assignment = new Assignment();
        assignment.setPipeline(pipeline);
        assignment.setOrganization(org);

        User user = new User();
        user.setId(UUID.randomUUID());

        Submission submission = new Submission();
        submission.setId(submissionId);
        submission.setAssignment(assignment);
        submission.setUser(user);
        submission.setStatus(SubmissionStatus.EVALUATED);
        submission.setEvaluatedAt(Instant.now());

        // Even when the evaluation pipeline produced narrative strengths and
        // development areas, the API must blank them for free-tier callers
        // — that block is part of the premium narrative report.
        OverallSummary summary = new OverallSummary();
        summary.setOverallScorePercentage(new BigDecimal("60.00"));
        summary.setStrengths(List.of("Leadership", "Vision"));
        summary.setDevelopmentAreas(List.of("Delegation"));
        summary.setRecommendations(List.of("Practice delegation"));

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(pillarEvaluationRepository.findBySubmissionId(submissionId)).thenReturn(List.of());
        when(overallSummaryRepository.findBySubmissionId(submissionId)).thenReturn(Optional.of(summary));
        when(premiumFeatureGuard.isPremiumOrSuperAdmin(orgId)).thenReturn(false);

        MemberResultsResponse response = memberResultsService.getResults(submissionId);

        assertThat(response.premiumFeaturesAvailable()).isFalse();
        assertThat(response.strengths()).isEmpty();
        assertThat(response.developmentAreas()).isEmpty();
    }

    @Test
    void getResults_nonSuperAdmin_stripsSurveyPayload() {
        // Even when a survey is paired and the member has submitted a response,
        // non-super-admin viewers (members and org admins) must see neither the
        // pairing summary nor the embedded answers — the post-assessment survey
        // is consumed by the platform Conductor only.
        UUID submissionId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID surveyId = UUID.randomUUID();

        Organization org = new Organization();
        org.setId(orgId);
        org.setSubscriptionTier(SubscriptionTier.PREMIUM);

        Pipeline pipeline = new Pipeline();
        pipeline.setName("Pipeline With Survey");
        pipeline.setPostCompletionSurveyId(surveyId);

        Assignment assignment = new Assignment();
        assignment.setPipeline(pipeline);
        assignment.setOrganization(org);

        User user = new User();
        user.setId(UUID.randomUUID());

        Submission submission = new Submission();
        submission.setId(submissionId);
        submission.setAssignment(assignment);
        submission.setUser(user);
        submission.setStatus(SubmissionStatus.EVALUATED);
        submission.setEvaluatedAt(Instant.now());

        OverallSummary summary = new OverallSummary();
        summary.setOverallScorePercentage(new BigDecimal("70.00"));

        when(submissionRepository.findById(submissionId)).thenReturn(Optional.of(submission));
        when(pillarEvaluationRepository.findBySubmissionId(submissionId)).thenReturn(List.of());
        when(overallSummaryRepository.findBySubmissionId(submissionId)).thenReturn(Optional.of(summary));
        when(premiumFeatureGuard.isPremiumOrSuperAdmin(orgId)).thenReturn(true);
        // Default principal seeded by @BeforeEach is a MEMBER, not a super admin.

        MemberResultsResponse response = memberResultsService.getResults(submissionId);

        assertThat(response.surveyResponse()).isNull();
        assertThat(response.survey()).isNull();
    }

    @Test
    void getPillarDetail_returnsFullAiFeedback() {
        UUID submissionId = UUID.randomUUID();
        UUID pillarId = UUID.randomUUID();

        Pillar pillar = new Pillar();
        pillar.setId(pillarId);
        pillar.setName("Strategy");
        pillar.setIconKey("target");

        PillarEvaluation eval = new PillarEvaluation();
        eval.setPillar(pillar);
        eval.setScorePercentage(new BigDecimal("85.00"));
        eval.setMaturityLabel("Elite");
        eval.setAiScoreMeans("Excellent strategic thinking");
        eval.setAiWhatsWorking(List.of("Vision setting", "Data-driven decisions"));
        eval.setAiWhatCanImprove(List.of("Long-term planning"));
        eval.setAiBusinessRelevance("Drives organizational growth");

        when(pillarEvaluationRepository.findBySubmissionIdAndPillarId(submissionId, pillarId))
                .thenReturn(List.of(eval));

        PillarDetailResponse response = memberResultsService.getPillarDetail(submissionId, pillarId);

        assertThat(response.pillarName()).isEqualTo("Strategy");
        assertThat(response.scorePercentage()).isEqualByComparingTo(new BigDecimal("85.00"));
        assertThat(response.whatThisScoreMeans()).isEqualTo("Excellent strategic thinking");
        assertThat(response.whatsWorking()).containsExactly("Vision setting", "Data-driven decisions");
    }

    @Test
    void getHistory_returnsGroupedByPipeline() {
        UUID userId = UUID.randomUUID();

        Pipeline pipeline = new Pipeline();
        pipeline.setId(UUID.randomUUID());
        pipeline.setName("Leadership Assessment");

        Assignment assignment = new Assignment();
        assignment.setPipeline(pipeline);

        Submission submission = new Submission();
        submission.setId(UUID.randomUUID());
        submission.setAssignment(assignment);
        submission.setStatus(SubmissionStatus.EVALUATED);
        submission.setEvaluatedAt(Instant.now());

        OverallSummary summary = new OverallSummary();
        summary.setSubmission(submission);
        summary.setOverallScorePercentage(new BigDecimal("72.00"));
        summary.setGeneratedAt(Instant.now());

        when(overallSummaryRepository.findByUserIdOrderByGeneratedAtDesc(userId))
                .thenReturn(List.of(summary));

        MemberHistoryResponse response = memberResultsService.getHistory(userId);

        assertThat(response.pipelines()).hasSize(1);
        assertThat(response.pipelines().getFirst().pipelineName()).isEqualTo("Leadership Assessment");
        assertThat(response.pipelines().getFirst().submissions()).hasSize(1);
    }
}
