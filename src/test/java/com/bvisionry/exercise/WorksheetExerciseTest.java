package com.bvisionry.exercise;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.exercise.dto.SaveExerciseAnswersRequest;
import com.bvisionry.exercise.dto.SaveExerciseRowsRequest;
import com.bvisionry.exercise.entity.ExerciseAssignment;
import com.bvisionry.exercise.entity.ExerciseSubmission;
import com.bvisionry.exercise.entity.ExerciseSubmissionStatus;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.WorksheetBlock;
import com.bvisionry.exercise.entity.WorksheetBlockType;
import com.bvisionry.exercise.repository.ExerciseColumnRepository;
import com.bvisionry.exercise.repository.ExerciseCommentRepository;
import com.bvisionry.exercise.repository.ExerciseRowRepository;
import com.bvisionry.exercise.repository.ExerciseSubmissionRepository;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.context.ApplicationEventPublisher;

import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.lenient;

/** Worksheet-kind coverage: answer saves, submit validation, block rules. */
@ExtendWith(MockitoExtension.class)
class WorksheetExerciseTest {

    @Mock private ExerciseSubmissionRepository submissionRepository;
    @Mock private ExerciseRowRepository rowRepository;
    @Mock private ExerciseColumnRepository columnRepository;
    @Mock private ExerciseCommentRepository commentRepository;
    @Mock private com.bvisionry.common.media.MediaUrlPort mediaUrlPort;
    @Mock private AuditService auditService;
    @Mock private ApplicationEventPublisher eventPublisher;

    @InjectMocks private ExerciseSubmissionService service;

    private UUID submissionId;
    private UUID userId;
    private ExerciseSubmission submission;
    private ExerciseTemplate template;

    private final UUID textBlockId = UUID.randomUUID();
    private final UUID checkboxBlockId = UUID.randomUUID();
    private final UUID tableBlockId = UUID.randomUUID();

    private static WorksheetBlock block(UUID id, WorksheetBlockType type, String label,
                                        boolean required, Map<String, Object> config) {
        return new WorksheetBlock(id, type, label, required, config);
    }

    @BeforeEach
    void setUp() {
        submissionId = UUID.randomUUID();
        userId = UUID.randomUUID();

        template = new ExerciseTemplate();
        template.setId(UUID.randomUUID());
        template.setName("Worksheet");
        template.setKind(ExerciseTemplateKind.WORKSHEET);
        template.setBlocks(List.of(
                block(textBlockId, WorksheetBlockType.TEXT, "Start here", true, Map.of()),
                block(checkboxBlockId, WorksheetBlockType.CHECKBOXES, "Check your words", false,
                        Map.of("options", List.of(
                                Map.of("id", "opt-1", "label", "The Invisible They"),
                                Map.of("id", "opt-2", "label", "The Amplifiers")))),
                block(tableBlockId, WorksheetBlockType.TABLE, "Sort it", false,
                        Map.of("columns", List.of(
                                Map.of("id", "col-facts", "name", "Hard facts"),
                                Map.of("id", "col-choices", "name", "Choices"))))));

        Organization org = new Organization();
        org.setId(UUID.randomUUID());

        User member = new User();
        member.setId(userId);
        member.setName("Member");
        member.setRole(UserRole.MEMBER);

        ExerciseAssignment assignment = new ExerciseAssignment();
        assignment.setId(UUID.randomUUID());
        assignment.setTemplate(template);
        assignment.setOrganization(org);
        assignment.setUser(member);

        submission = new ExerciseSubmission();
        submission.setId(submissionId);
        submission.setAssignment(assignment);
        submission.setUser(member);
        submission.setStatus(ExerciseSubmissionStatus.IN_PROGRESS);

        lenient().when(submissionRepository.findById(submissionId))
                .thenReturn(java.util.Optional.of(submission));
        lenient().when(columnRepository.findByTemplateIdOrderByDisplayOrder(template.getId()))
                .thenReturn(List.of());
        lenient().when(rowRepository.findBySubmissionId(submissionId)).thenReturn(List.of());
        lenient().when(commentRepository.findBySubmissionIdOrderByCreatedAt(submissionId))
                .thenReturn(List.of());
    }

    @Test
    void saveAnswers_dropsUnknownBlocksAndBlankValues() {
        service.saveAnswers(submissionId, userId, new SaveExerciseAnswersRequest(Map.of(
                textBlockId.toString(), "My answer",
                checkboxBlockId.toString(), List.of(),
                UUID.randomUUID().toString(), "not a block")));

        assertThat(submission.getAnswers())
                .containsOnlyKeys(textBlockId.toString())
                .containsEntry(textBlockId.toString(), "My answer");
    }

    @Test
    void saveAnswers_changeOnReviewedSubmission_reopensAsSubmitted() {
        submission.setStatus(ExerciseSubmissionStatus.REVIEWED);
        submission.setReviewedAt(Instant.now());
        submission.setAnswers(new HashMap<>(Map.of(textBlockId.toString(), "old")));

        service.saveAnswers(submissionId, userId, new SaveExerciseAnswersRequest(
                Map.of(textBlockId.toString(), "new")));

        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.SUBMITTED);
        assertThat(submission.getReviewedAt()).isNull();
    }

    /** Absent and blank must persist identically — a no-op autosave is not an edit. */
    @Test
    void saveAnswers_identicalSaveOnReviewedSubmission_staysReviewed() {
        submission.setStatus(ExerciseSubmissionStatus.REVIEWED);
        submission.setReviewedAt(Instant.now());
        submission.setAnswers(new HashMap<>(Map.of(textBlockId.toString(), "same")));

        service.saveAnswers(submissionId, userId, new SaveExerciseAnswersRequest(Map.of(
                textBlockId.toString(), "same",
                checkboxBlockId.toString(), List.of(),
                tableBlockId.toString(), List.of(Map.of("col-facts", " ")))));

        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.REVIEWED);
        assertThat(submission.getReviewedAt()).isNotNull();
    }

    /** Wrong-shaped values and CONTENT-keyed values must not persist — they
     *  would satisfy the required-block submit gate while rendering as empty. */
    @Test
    void saveAnswers_dropsJunkShapesAndContentKeys() {
        UUID contentId = UUID.randomUUID();
        List<WorksheetBlock> withContent = new java.util.ArrayList<>(template.getBlocks());
        withContent.add(block(contentId, WorksheetBlockType.CONTENT, "Read", false, Map.of()));
        template.setBlocks(withContent);

        service.saveAnswers(submissionId, userId, new SaveExerciseAnswersRequest(Map.of(
                textBlockId.toString(), Map.of("x", 1),
                checkboxBlockId.toString(), "not-a-list",
                contentId.toString(), "smuggled")));

        assertThat(submission.getAnswers()).isEmpty();
    }

    /** A contentless "Add row" click is not an edit — it must not reopen review. */
    @Test
    void saveAnswers_blankAddedTableRow_staysReviewed() {
        submission.setStatus(ExerciseSubmissionStatus.REVIEWED);
        submission.setReviewedAt(Instant.now());
        submission.setAnswers(new HashMap<>(Map.of(
                tableBlockId.toString(), List.of(Map.of("col-facts", "x")))));

        service.saveAnswers(submissionId, userId, new SaveExerciseAnswersRequest(Map.of(
                tableBlockId.toString(), List.of(Map.of("col-facts", "x"), Map.of()))));

        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.REVIEWED);
        assertThat(submission.getReviewedAt()).isNotNull();
    }

    @Test
    void saveRows_onWorksheetTemplate_isRejected() {
        assertThatThrownBy(() -> service.saveRows(submissionId, userId,
                new SaveExerciseRowsRequest(List.of())))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("worksheet");
    }

    @Test
    void saveAnswers_onSheetTemplate_isRejected() {
        template.setKind(ExerciseTemplateKind.SHEET);

        assertThatThrownBy(() -> service.saveAnswers(submissionId, userId,
                new SaveExerciseAnswersRequest(Map.of())))
                .isInstanceOf(BadRequestException.class);
    }

    @Test
    void overrideAnswers_writesWithoutReopeningReview() {
        submission.setStatus(ExerciseSubmissionStatus.REVIEWED);
        submission.setReviewedAt(Instant.now());

        service.overrideAnswers(submission, new SaveExerciseAnswersRequest(
                Map.of(textBlockId.toString(), "admin value")));

        assertThat(submission.getAnswers()).containsEntry(textBlockId.toString(), "admin value");
        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.REVIEWED);
        assertThat(submission.getReviewedAt()).isNotNull();
    }

    @Test
    void submit_requiresAnyAnswer() {
        assertThatThrownBy(() -> service.submit(submissionId, userId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Fill in the worksheet");
    }

    @Test
    void submit_requiresRequiredBlocksAnswered() {
        submission.setAnswers(Map.of(checkboxBlockId.toString(), List.of("opt-1")));

        assertThatThrownBy(() -> service.submit(submissionId, userId))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Start here");
    }

    /** A worksheet of only CONTENT blocks collects nothing — reading it IS completing it. */
    @Test
    void submit_allContentWorksheet_needsNoAnswers() {
        template.setBlocks(List.of(
                block(UUID.randomUUID(), WorksheetBlockType.CONTENT, "Read", false, Map.of())));

        service.submit(submissionId, userId);

        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.SUBMITTED);
    }

    @Test
    void submit_withRequiredBlocksFilled_submits() {
        submission.setAnswers(Map.of(textBlockId.toString(), "Done"));

        service.submit(submissionId, userId);

        assertThat(submission.getStatus()).isEqualTo(ExerciseSubmissionStatus.SUBMITTED);
        assertThat(submission.getSubmittedAt()).isNotNull();
    }

    // ---- WorksheetBlocks rules --------------------------------------------

    @Test
    void validate_rejectsDuplicateIdsAndOptionlessCheckboxes() {
        UUID id = UUID.randomUUID();
        WorksheetBlock content = block(id, WorksheetBlockType.CONTENT, "A", false, Map.of());

        assertThatThrownBy(() -> WorksheetBlocks.validate(List.of(content, content)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("Duplicate");
        assertThatThrownBy(() -> WorksheetBlocks.validate(List.of(
                block(UUID.randomUUID(), WorksheetBlockType.CHECKBOXES, "B", false, Map.of()))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("options");
    }

    @Test
    void structureLock_allowsReorderAndCopyEdits_rejectsTypeAndMembershipChanges() {
        List<WorksheetBlock> existing = template.getBlocks();

        // Reorder + relabel: same ids, same types — fine.
        WorksheetBlocks.requireStructureCompatible(existing, List.of(
                block(checkboxBlockId, WorksheetBlockType.CHECKBOXES, "Renamed", true,
                        existing.get(1).config()),
                block(textBlockId, WorksheetBlockType.TEXT, "Start here", true, Map.of()),
                block(tableBlockId, WorksheetBlockType.TABLE, "Sort it", false,
                        existing.get(2).config())));

        // Type change on a kept id — rejected.
        assertThatThrownBy(() -> WorksheetBlocks.requireStructureCompatible(existing, List.of(
                block(textBlockId, WorksheetBlockType.CHECKBOXES, "Start here", true,
                        Map.of("options", List.of(Map.of("id", "x", "label", "X")))),
                existing.get(1), existing.get(2))))
                .isInstanceOf(BadRequestException.class);

        // Removed block — rejected.
        assertThatThrownBy(() -> WorksheetBlocks.requireStructureCompatible(existing,
                List.of(existing.get(0), existing.get(1))))
                .isInstanceOf(BadRequestException.class);

        // Removed option on a kept CHECKBOXES block — member answers key on
        // option ids, so removals are rejected (additions stay allowed).
        assertThatThrownBy(() -> WorksheetBlocks.requireStructureCompatible(existing, List.of(
                existing.get(0),
                block(checkboxBlockId, WorksheetBlockType.CHECKBOXES, "Check your words", false,
                        Map.of("options", List.of(
                                Map.of("id", "opt-1", "label", "The Invisible They")))),
                existing.get(2))))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("options");
        WorksheetBlocks.requireStructureCompatible(existing, List.of(
                existing.get(0),
                block(checkboxBlockId, WorksheetBlockType.CHECKBOXES, "Check your words", false,
                        Map.of("options", List.of(
                                Map.of("id", "opt-1", "label", "The Invisible They"),
                                Map.of("id", "opt-2", "label", "The Amplifiers"),
                                Map.of("id", "opt-3", "label", "New option")))),
                existing.get(2)));
    }

    @Test
    void answerText_resolvesOptionLabelsAndTableColumns() {
        WorksheetBlock checkboxes = template.getBlocks().get(1);
        assertThat(WorksheetBlocks.answerText(checkboxes, List.of("opt-1", "opt-2")))
                .isEqualTo("The Invisible They, The Amplifiers");

        WorksheetBlock table = template.getBlocks().get(2);
        assertThat(WorksheetBlocks.answerText(table, List.of(
                Map.of("col-facts", "Budget cut", "col-choices", "Book a call"),
                Map.of("col-choices", "Rewrite proposal"))))
                .isEqualTo("Hard facts: Budget cut | Choices: Book a call\nChoices: Rewrite proposal");
    }
}
