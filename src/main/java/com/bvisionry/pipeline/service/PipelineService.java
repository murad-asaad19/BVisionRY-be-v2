package com.bvisionry.pipeline.service;

import com.bvisionry.assessment.AnswerRepository;
import com.bvisionry.assessment.AssignmentRepository;
import com.bvisionry.assessment.PipelineAutoAssignmentRepository;
import com.bvisionry.assessment.PipelineAutoAssignmentService;
import com.bvisionry.assessment.SubmissionRepository;
import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.common.enums.PillarType;
import com.bvisionry.common.enums.PipelineStatus;
import com.bvisionry.common.enums.QuestionType;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.validation.ValidExternalUrlValidator;
import com.bvisionry.pipeline.SystemQuestion;
import com.bvisionry.survey.repository.SurveyRepository;
import com.bvisionry.pipeline.dto.AssignedOrgSummary;
import com.bvisionry.pipeline.dto.PillarPreviewResponse;
import com.bvisionry.pipeline.dto.PillarResponse;
import com.bvisionry.pipeline.dto.PipelineCreateRequest;
import com.bvisionry.pipeline.dto.PipelinePreviewResponse;
import com.bvisionry.pipeline.dto.PipelineResponse;
import com.bvisionry.pipeline.dto.PipelineStatusRequest;
import com.bvisionry.pipeline.dto.PipelineSummaryResponse;
import com.bvisionry.pipeline.dto.PipelineMetadataUpdateRequest;
import com.bvisionry.pipeline.dto.PipelineUpdateRequest;
import com.bvisionry.pipeline.dto.QuestionPreviewResponse;
import com.bvisionry.pipeline.dto.QuestionResponse;
import com.bvisionry.pipeline.entity.Pillar;
import com.bvisionry.pipeline.entity.Pipeline;
import com.bvisionry.pipeline.entity.Question;
import com.bvisionry.pipeline.repository.PipelineRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

@Service
@RequiredArgsConstructor
public class PipelineService {

    private final PipelineRepository pipelineRepository;
    private final AuditService auditService;
    private final AssignmentRepository assignmentRepository;
    private final SubmissionRepository submissionRepository;
    private final AnswerRepository answerRepository;
    private final PipelineAutoAssignmentService pipelineAutoAssignmentService;
    private final PipelineAutoAssignmentRepository pipelineAutoAssignmentRepository;
    private final SurveyRepository surveyRepository;

    @Transactional
    public PipelineResponse create(PipelineCreateRequest request) {
        // Derive the creator from the authenticated principal — never trust a
        // client-supplied {@code createdBy} field for audit attribution.
        UUID creatorId = SecurityUtils.getCurrentUserId();

        Pipeline pipeline = new Pipeline();
        pipeline.setName(request.name());
        pipeline.setDescription(request.description());
        pipeline.setCreatedBy(creatorId);
        pipeline.setFreeTierPrompt(request.freeTierPrompt());
        pipeline.setOverallSummaryPrompt(request.overallSummaryPrompt());
        pipeline.setStatus(PipelineStatus.DRAFT);
        pipeline.setVersion(1);

        // Every pipeline starts with a locked Personal pillar that always carries
        // Name + Gender. The AI relies on these to address the user correctly.
        Pillar personal = buildDefaultPersonalPillar(pipeline);
        pipeline.setPillars(new ArrayList<>(List.of(personal)));

        Pipeline saved = pipelineRepository.save(pipeline);

        auditService.log(creatorId, null, "PIPELINE_CREATED", "Pipeline",
                saved.getId(), Map.of("name", saved.getName()));

        return toFullResponse(saved);
    }

    /**
     * Build the always-present "General Information" pillar with the locked
     * system questions (First Name, Last Name, Gender). Display order 0 so it
     * shows first. First + Last render side-by-side via the sameRow layout hint.
     */
    static Pillar buildDefaultPersonalPillar(Pipeline pipeline) {
        Pillar personal = new Pillar();
        personal.setPipeline(pipeline);
        personal.setName("General Information");
        personal.setDescription("Basic information used to personalise your assessment results.");
        personal.setType(PillarType.PERSONAL);
        personal.setWeight(BigDecimal.ZERO);
        personal.setDisplayOrder(0);
        personal.setMaturityThresholds(Map.of());

        Question firstName = new Question();
        firstName.setPillar(personal);
        firstName.setType(QuestionType.FREE_TEXT);
        firstName.setPromptText("First Name");
        firstName.setDisplayOrder(0);
        firstName.setRequired(true);
        firstName.setWeight(BigDecimal.ONE);
        firstName.setSystemKey(SystemQuestion.FIRST_NAME);

        Question lastName = new Question();
        lastName.setPillar(personal);
        lastName.setType(QuestionType.FREE_TEXT);
        lastName.setPromptText("Last Name");
        lastName.setDisplayOrder(1);
        lastName.setRequired(true);
        lastName.setWeight(BigDecimal.ONE);
        Map<String, Object> lastNameConfig = new LinkedHashMap<>();
        lastNameConfig.put("layout", Map.of("sameRow", true));
        lastName.setConfigJson(lastNameConfig);
        lastName.setSystemKey(SystemQuestion.LAST_NAME);

        Question gender = new Question();
        gender.setPillar(personal);
        gender.setType(QuestionType.MULTIPLE_CHOICE);
        gender.setPromptText("Gender");
        gender.setDisplayOrder(2);
        // Gender is system-managed (locked, undeletable) but optional to answer:
        // it only personalises pronoun choice, which the AI handles gracefully
        // when absent. See EvaluationEngine#buildUserContext.
        gender.setRequired(false);
        gender.setWeight(BigDecimal.ONE);
        Map<String, Object> genderConfig = new LinkedHashMap<>();
        genderConfig.put("options", List.of("Male", "Female"));
        genderConfig.put("allowMultiple", false);
        gender.setConfigJson(genderConfig);
        gender.setSystemKey(SystemQuestion.GENDER);

        personal.setQuestions(new ArrayList<>(List.of(firstName, lastName, gender)));
        return personal;
    }

    @Transactional(readOnly = true)
    public PipelineResponse getById(UUID id) {
        Pipeline pipeline = pipelineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", id.toString()));
        return toFullResponse(pipeline);
    }

    @Transactional(readOnly = true)
    public List<PipelineSummaryResponse> listAll(PipelineStatus status) {
        List<Pipeline> pipelines;
        if (status != null) {
            pipelines = pipelineRepository.findByStatusOrderByUpdatedAtDesc(status);
        } else {
            pipelines = pipelineRepository.findAllByOrderByUpdatedAtDesc();
        }
        return toSummaryResponsesWithOrgs(pipelines);
    }

    @Transactional
    public PipelineResponse update(UUID id, PipelineUpdateRequest request) {
        Pipeline pipeline = pipelineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", id.toString()));

        requireDraft(pipeline);

        pipeline.setName(request.name());
        pipeline.setDescription(request.description());
        if (request.freeTierPrompt() != null) {
            pipeline.setFreeTierPrompt(request.freeTierPrompt());
        }
        if (request.overallSummaryPrompt() != null) {
            pipeline.setOverallSummaryPrompt(request.overallSummaryPrompt());
        }

        Pipeline saved = pipelineRepository.save(pipeline);
        return toFullResponse(saved);
    }

    /**
     * Updates only the human-readable metadata (name, description). Allowed in any
     * status because it doesn't change the pipeline's structure or scoring — useful
     * for fixing typos or rebranding a published or archived pipeline.
     */
    @Transactional
    public PipelineResponse updateMetadata(UUID id, PipelineMetadataUpdateRequest request) {
        Pipeline pipeline = pipelineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", id.toString()));

        pipeline.setName(request.name());
        pipeline.setDescription(request.description());
        pipeline.setFreeTierPrompt(request.freeTierPrompt());
        pipeline.setOverallSummaryPrompt(request.overallSummaryPrompt());

        Pipeline saved = pipelineRepository.save(pipeline);
        return toFullResponse(saved);
    }

    @Transactional
    public PipelineResponse transitionStatus(UUID id, PipelineStatusRequest request) {
        Pipeline pipeline = pipelineRepository.findByIdWithPillarsAndQuestions(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", id.toString()));

        PipelineStatus current = pipeline.getStatus();
        PipelineStatus target = request.status();

        validateTransition(current, target);

        if (target == PipelineStatus.PUBLISHED) {
            validatePublishReadiness(pipeline);
        }

        // When archiving or reverting to draft, clean up assignments and submissions.
        // Only ARCHIVED also drops the auto-assign rules — a revert-to-draft is
        // a routine "fix and republish" cycle, and silently wiping every rule
        // on each edit pass would surprise admins (rules would vanish without
        // an audit trail and have to be re-created after every republish).
        if (target == PipelineStatus.ARCHIVED) {
            cleanupPipelineAssignments(id, /* dropAutoAssignRules */ true);
        } else if (current == PipelineStatus.PUBLISHED && target == PipelineStatus.DRAFT) {
            cleanupPipelineAssignments(id, /* dropAutoAssignRules */ false);
        }

        pipeline.setStatus(target);
        Pipeline saved = pipelineRepository.save(pipeline);

        auditService.log(null, null, "PIPELINE_STATUS_CHANGED", "Pipeline",
                saved.getId(), Map.of("from", current.name(), "to", target.name()));

        return toFullResponse(saved);
    }

    @Transactional
    public void deletePipeline(UUID id) {
        Pipeline pipeline = pipelineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", id.toString()));

        // Pipeline is going away entirely — drop everything that points at it.
        cleanupPipelineAssignments(id, /* dropAutoAssignRules */ true);

        // Delete the pipeline (cascades to pillars and questions)
        pipelineRepository.delete(pipeline);

        auditService.log(null, null, "PIPELINE_DELETED", "Pipeline", id,
                Map.of("name", pipeline.getName()));
    }

    /**
     * Discards in-flight work for a pipeline. {@code dropAutoAssignRules} is
     * only true when the pipeline is being permanently retired (ARCHIVED or
     * deleted) — a republish cycle preserves rules so future joiners continue
     * to receive the pipeline once it is back in PUBLISHED state.
     */
    private void cleanupPipelineAssignments(UUID pipelineId, boolean dropAutoAssignRules) {
        var assignments = assignmentRepository.findByPipelineId(pipelineId);
        for (var assignment : assignments) {
            var submissions = submissionRepository.findByAssignmentId(assignment.getId());
            for (var submission : submissions) {
                answerRepository.deleteAll(answerRepository.findBySubmissionId(submission.getId()));
            }
            submissionRepository.deleteAll(submissions);
        }
        assignmentRepository.deleteAll(assignments);

        if (dropAutoAssignRules) {
            pipelineAutoAssignmentService.deleteRulesForPipeline(pipelineId);
        }
    }

    @Transactional
    public PipelineResponse createNewVersion(UUID id) {
        Pipeline original = pipelineRepository.findByIdWithPillarsAndQuestions(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", id.toString()));

        if (original.getStatus() == PipelineStatus.DRAFT) {
            throw new IllegalOperationException(
                    "Only Published or Archived pipelines can be versioned. Current status: DRAFT");
        }

        int maxVersion = pipelineRepository.findMaxVersionByName(original.getName()).orElse(0);

        Pipeline cloned = new Pipeline();
        cloned.setName(original.getName());
        cloned.setDescription(original.getDescription());
        cloned.setCreatedBy(original.getCreatedBy());
        cloned.setStatus(PipelineStatus.DRAFT);
        cloned.setVersion(maxVersion + 1);

        List<Pillar> clonedPillars = new ArrayList<>();
        for (Pillar originalPillar : original.getPillars()) {
            Pillar clonedPillar = new Pillar();
            clonedPillar.setPipeline(cloned);
            clonedPillar.setName(originalPillar.getName());
            clonedPillar.setDescription(originalPillar.getDescription());
            clonedPillar.setIconKey(originalPillar.getIconKey());
            clonedPillar.setWeight(originalPillar.getWeight());
            clonedPillar.setDisplayOrder(originalPillar.getDisplayOrder());
            clonedPillar.setAiRubricInstructions(originalPillar.getAiRubricInstructions());

            if (originalPillar.getMaturityThresholds() != null) {
                clonedPillar.setMaturityThresholds(new LinkedHashMap<>(originalPillar.getMaturityThresholds()));
            }

            List<Question> clonedQuestions = new ArrayList<>();
            for (Question originalQuestion : originalPillar.getQuestions()) {
                Question clonedQuestion = new Question();
                clonedQuestion.setPillar(clonedPillar);
                clonedQuestion.setType(originalQuestion.getType());
                clonedQuestion.setPromptText(originalQuestion.getPromptText());
                clonedQuestion.setDisplayOrder(originalQuestion.getDisplayOrder());
                clonedQuestion.setRequired(originalQuestion.isRequired());
                clonedQuestion.setWeight(originalQuestion.getWeight());
                clonedQuestion.setSystemKey(originalQuestion.getSystemKey());

                if (originalQuestion.getConfigJson() != null) {
                    clonedQuestion.setConfigJson(new LinkedHashMap<>(originalQuestion.getConfigJson()));
                }

                clonedQuestions.add(clonedQuestion);
            }
            clonedPillar.setQuestions(clonedQuestions);
            clonedPillars.add(clonedPillar);
        }
        cloned.setPillars(clonedPillars);

        Pipeline saved = pipelineRepository.save(cloned);

        auditService.log(null, null, "PIPELINE_VERSIONED", "Pipeline",
                saved.getId(), Map.of("originalId", id.toString(), "newVersion", saved.getVersion()));

        return toFullResponse(saved);
    }

    @Transactional
    public PipelineResponse duplicate(UUID id) {
        Pipeline original = pipelineRepository.findByIdWithPillarsAndQuestions(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", id.toString()));

        Pipeline cloned = new Pipeline();
        cloned.setName(original.getName() + " (Copy)");
        cloned.setDescription(original.getDescription());
        cloned.setCreatedBy(original.getCreatedBy());
        cloned.setStatus(PipelineStatus.DRAFT);
        cloned.setVersion(1);
        cloned.setFreeTierPrompt(original.getFreeTierPrompt());
        cloned.setOverallSummaryPrompt(original.getOverallSummaryPrompt());

        List<Pillar> clonedPillars = new ArrayList<>();
        for (Pillar originalPillar : original.getPillars()) {
            Pillar clonedPillar = clonePillar(originalPillar, cloned);
            clonedPillars.add(clonedPillar);
        }
        cloned.setPillars(clonedPillars);

        Pipeline saved = pipelineRepository.save(cloned);

        auditService.log(null, null, "PIPELINE_DUPLICATED", "Pipeline",
                saved.getId(), Map.of("originalId", id.toString(), "name", saved.getName()));

        return toFullResponse(saved);
    }

    Pillar clonePillar(Pillar original, Pipeline targetPipeline) {
        Pillar cloned = new Pillar();
        cloned.setPipeline(targetPipeline);
        cloned.setName(original.getName());
        cloned.setDescription(original.getDescription());
        cloned.setIconKey(original.getIconKey());
        cloned.setType(original.getType());
        cloned.setWeight(original.getWeight());
        cloned.setDisplayOrder(original.getDisplayOrder());
        cloned.setAiRubricInstructions(original.getAiRubricInstructions());

        if (original.getMaturityThresholds() != null) {
            cloned.setMaturityThresholds(new LinkedHashMap<>(original.getMaturityThresholds()));
        }

        List<Question> clonedQuestions = new ArrayList<>();
        for (Question originalQuestion : original.getQuestions()) {
            Question clonedQuestion = new Question();
            clonedQuestion.setPillar(cloned);
            clonedQuestion.setType(originalQuestion.getType());
            clonedQuestion.setPromptText(originalQuestion.getPromptText());
            clonedQuestion.setDisplayOrder(originalQuestion.getDisplayOrder());
            clonedQuestion.setRequired(originalQuestion.isRequired());
            clonedQuestion.setWeight(originalQuestion.getWeight());
            clonedQuestion.setSystemKey(originalQuestion.getSystemKey());
            if (originalQuestion.getConfigJson() != null) {
                clonedQuestion.setConfigJson(new LinkedHashMap<>(originalQuestion.getConfigJson()));
            }
            clonedQuestions.add(clonedQuestion);
        }
        cloned.setQuestions(clonedQuestions);
        return cloned;
    }

    @Transactional(readOnly = true)
    public List<PipelineSummaryResponse> getPublishedCatalog() {
        return toSummaryResponsesWithOrgs(pipelineRepository.findByStatus(PipelineStatus.PUBLISHED));
    }

    @Transactional(readOnly = true)
    public PipelinePreviewResponse getPreview(UUID id) {
        Pipeline pipeline = pipelineRepository.findByIdWithPillarsAndQuestions(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", id.toString()));

        return new PipelinePreviewResponse(
                pipeline.getId(),
                pipeline.getName(),
                pipeline.getDescription(),
                pipeline.getVersion(),
                pipeline.getPillars().stream().map(this::toPillarPreview).toList()
        );
    }

    // --- internal: require Draft status ---

    @Transactional
    public PipelineResponse setPostCompletion(UUID pipelineId,
            com.bvisionry.pipeline.dto.PipelinePostCompletionRequest request) {
        Pipeline pipeline = pipelineRepository.findByIdWithPillarsAndQuestions(pipelineId)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", pipelineId.toString()));

        boolean hasSurvey = request.surveyId() != null;
        boolean hasUrl = request.externalUrl() != null && !request.externalUrl().isBlank();
        if (hasSurvey && hasUrl) {
            throw new BadRequestException(
                    "Set either a survey OR an external URL, not both.");
        }

        if (hasSurvey) {
            surveyRepository.findById(request.surveyId())
                    .orElseThrow(() -> new ResourceNotFoundException("Survey", request.surveyId().toString()));
            pipeline.setPostCompletionSurveyId(request.surveyId());
            pipeline.setPostCompletionExternalUrl(null);
        } else if (hasUrl) {
            String trimmed = request.externalUrl().trim();
            // Restrict to http(s):// — javascript:/data:/file: would let an admin
            // plant URI-scheme XSS or local-file exfiltration into the post-
            // submission redirect / results-ready CTA that members click.
            requireWebUrl(trimmed);
            pipeline.setPostCompletionSurveyId(null);
            pipeline.setPostCompletionExternalUrl(trimmed);
        } else {
            pipeline.setPostCompletionSurveyId(null);
            pipeline.setPostCompletionExternalUrl(null);
        }
        pipeline.setPostCompletionLabel(
                request.label() != null && !request.label().isBlank() ? request.label().trim() : null);

        Pipeline saved = pipelineRepository.save(pipeline);
        return toFullResponse(saved);
    }

    void requireDraft(Pipeline pipeline) {
        if (pipeline.getStatus() != PipelineStatus.DRAFT) {
            throw new IllegalOperationException(
                    "Cannot modify a " + pipeline.getStatus() + " pipeline. Create a new version first.");
        }
    }

    private static void requireWebUrl(String url) {
        if (!ValidExternalUrlValidator.isSafePublicUrl(url)) {
            throw new BadRequestException(
                    "Post-completion URL must be a valid http(s) URL that does not point to a local or private host");
        }
    }

    Pipeline findPipelineOrThrow(UUID id) {
        return pipelineRepository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("Pipeline", id.toString()));
    }

    // --- validation helpers ---

    private void validateTransition(PipelineStatus current, PipelineStatus target) {
        boolean valid = switch (current) {
            case DRAFT -> target == PipelineStatus.PUBLISHED;
            case PUBLISHED -> target == PipelineStatus.ARCHIVED || target == PipelineStatus.DRAFT;
            case ARCHIVED -> target == PipelineStatus.DRAFT;
        };
        if (!valid) {
            throw new IllegalOperationException(
                    "Invalid status transition from " + current + " to " + target);
        }
    }

    private void validatePublishReadiness(Pipeline pipeline) {
        if (pipeline.getPillars() == null || pipeline.getPillars().isEmpty()) {
            throw new BadRequestException(
                    "Cannot publish pipeline: must have at least 1 pillar");
        }
        for (Pillar pillar : pipeline.getPillars()) {
            if (pillar.getQuestions() == null || pillar.getQuestions().isEmpty()) {
                throw new BadRequestException(
                        "Cannot publish pipeline: pillar '" + pillar.getName() +
                        "' must have at least 1 question");
            }
        }
    }

    // --- mappers ---

    PipelineResponse toFullResponse(Pipeline p) {
        List<PillarResponse> pillarResponses = p.getPillars() != null
                ? p.getPillars().stream().map(this::toPillarResponse).toList()
                : List.of();

        // Post-completion fields are SUPER_ADMIN-only. Default to hidden (null/false)
        // when the current caller isn't SUPER_ADMIN or no auth is on the context.
        boolean includePostCompletion = isSuperAdminSafe();

        return new PipelineResponse(
                p.getId(), p.getName(), p.getDescription(), p.getVersion(),
                p.getStatus(), p.getCreatedBy(),
                p.getFreeTierPrompt(),
                p.getOverallSummaryPrompt(),
                pillarResponses, p.getCreatedAt(), p.getUpdatedAt(),
                includePostCompletion ? p.getPostCompletionSurveyId() : null,
                includePostCompletion ? p.getPostCompletionExternalUrl() : null,
                includePostCompletion ? p.getPostCompletionLabel() : null
        );
    }

    private static boolean isSuperAdminSafe() {
        try {
            return SecurityUtils.isSuperAdmin();
        } catch (RuntimeException ignored) {
            return false;
        }
    }

    private PillarResponse toPillarResponse(Pillar pillar) {
        List<QuestionResponse> questionResponses = pillar.getQuestions() != null
                ? pillar.getQuestions().stream().map(this::toQuestionResponse).toList()
                : List.of();

        return new PillarResponse(
                pillar.getId(), pillar.getPipeline().getId(),
                pillar.getName(), pillar.getDescription(), pillar.getIconKey(),
                pillar.getWeight(), pillar.getDisplayOrder(),
                pillar.getType().name(),
                pillar.getAiRubricInstructions(), pillar.getMaturityThresholds(),
                questionResponses, pillar.getCreatedAt(), pillar.getUpdatedAt()
        );
    }

    private QuestionResponse toQuestionResponse(Question q) {
        return new QuestionResponse(
                q.getId(), q.getPillar().getId(), q.getType(), q.getPromptText(),
                q.getDisplayOrder(), q.isRequired(), q.getWeight(),
                q.getConfigJson(), q.getSystemKey(),
                q.getCreatedAt(), q.getUpdatedAt()
        );
    }

    private PillarPreviewResponse toPillarPreview(Pillar pillar) {
        List<QuestionPreviewResponse> questionPreviews = pillar.getQuestions() != null
                ? pillar.getQuestions().stream().map(this::toQuestionPreview).toList()
                : List.of();

        return new PillarPreviewResponse(
                pillar.getId(), pillar.getName(), pillar.getDescription(),
                pillar.getIconKey(), pillar.getDisplayOrder(), questionPreviews
        );
    }

    private QuestionPreviewResponse toQuestionPreview(Question q) {
        return new QuestionPreviewResponse(
                q.getId(), q.getType(), q.getPromptText(),
                q.getDisplayOrder(), q.isRequired(), q.getConfigJson()
        );
    }

    private List<PipelineSummaryResponse> toSummaryResponsesWithOrgs(List<Pipeline> pipelines) {
        if (pipelines.isEmpty()) {
            return List.of();
        }
        List<UUID> ids = pipelines.stream().map(Pipeline::getId).toList();

        // Per-pipeline map of orgId → mutable summary. Two passes: first
        // collect orgs reached via assignments (autoAssign defaults to false),
        // then mark or insert orgs that have an auto-assign rule. An org with
        // only a rule and no current assignments still appears in the list.
        Map<UUID, Map<UUID, AssignedOrgSummary>> byPipeline = new java.util.HashMap<>();
        for (Object[] row : assignmentRepository.findDistinctOrgsByPipelineIds(ids)) {
            UUID pipelineId = (UUID) row[0];
            UUID orgId = (UUID) row[1];
            String orgName = (String) row[2];
            byPipeline
                    .computeIfAbsent(pipelineId, k -> new java.util.LinkedHashMap<>())
                    .put(orgId, new AssignedOrgSummary(orgId, orgName, false));
        }
        for (Object[] row : pipelineAutoAssignmentRepository.findDistinctOrgsByPipelineIds(ids)) {
            UUID pipelineId = (UUID) row[0];
            UUID orgId = (UUID) row[1];
            String orgName = (String) row[2];
            // Either insert a fresh entry, or flip an existing manual-only
            // entry to autoAssign=true.
            byPipeline
                    .computeIfAbsent(pipelineId, k -> new java.util.LinkedHashMap<>())
                    .compute(orgId, (k, existing) -> existing == null
                            ? new AssignedOrgSummary(orgId, orgName, true)
                            : new AssignedOrgSummary(existing.id(), existing.name(), true));
        }

        return pipelines.stream()
                .map(p -> {
                    Map<UUID, AssignedOrgSummary> orgs = byPipeline.get(p.getId());
                    List<AssignedOrgSummary> orgList = orgs == null
                            ? List.of()
                            : List.copyOf(orgs.values());
                    return toSummaryResponse(p, orgList);
                })
                .toList();
    }

    private PipelineSummaryResponse toSummaryResponse(Pipeline p, List<AssignedOrgSummary> assignedOrganizations) {
        int pillarCount = p.getPillars() != null
                ? (int) p.getPillars().stream().filter(pillar -> pillar.getType() != PillarType.PERSONAL).count()
                : 0;
        return new PipelineSummaryResponse(
                p.getId(), p.getName(), p.getDescription(), p.getVersion(),
                p.getStatus(), p.getCreatedBy(),
                pillarCount, assignedOrganizations, p.getCreatedAt(), p.getUpdatedAt()
        );
    }
}
