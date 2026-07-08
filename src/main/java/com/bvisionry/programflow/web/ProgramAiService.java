package com.bvisionry.programflow.web;

import java.io.IOException;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.ai.StreamingChatPort;
import com.bvisionry.common.enums.PromptType;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.programflow.domain.ProgramModule;
import com.bvisionry.programflow.domain.ProgramTask;
import com.bvisionry.programflow.domain.ProgramTaskField;
import com.bvisionry.programflow.dto.ModuleDraft;
import com.bvisionry.programflow.repository.ProgramModuleRepository;
import com.bvisionry.programflow.repository.ProgramSettingsRepository;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.MapperFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.json.JsonMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Program Flow AI: the admin module composer and the learner coach hint, both
 * streamed over SSE through the feature-neutral {@link StreamingChatPort}.
 *
 * <p>SSE protocol — composer: {@code status} events (real generation phases),
 * then one {@code draft} event carrying a {@link ModuleDraft} JSON, or one
 * {@code error} event. Coach: {@code token} events, then {@code done} / {@code error}.
 */
@Slf4j
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ProgramAiService {

    private static final long SSE_TIMEOUT_MS = 180_000L;
    private static final int COMPOSER_MAX_TOKENS = 4096;
    private static final int COACH_MAX_TOKENS = 300;

    private final ProgramModuleRepository modules;
    private final ProgramSettingsRepository settings;
    private final MyProgramService myProgram;
    private final CohortService cohortService;
    private final StreamingChatPort chat;

    /** Lenient mapper for model output: case-insensitive enums, ignore extras. */
    private final ObjectMapper draftMapper = JsonMapper.builder()
            .enable(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS)
            .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
            .build()
            .registerModule(new com.fasterxml.jackson.datatype.jsr310.JavaTimeModule());

    // --------------------------------------------------------------- composer

    public SseEmitter compose(UUID orgId, UUID cohortId, String prompt) {
        // Tenant guard: the cohort must belong to the org in the path (mirrors
        // every other admin program endpoint) so a foreign cohortId can't leak
        // another org's curriculum into the composer prompt.
        cohortService.require(orgId, cohortId);
        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        AtomicBoolean drafting = new AtomicBoolean(false);

        emit(emitter, "status", "Reading your existing curriculum…");
        String userMessage = composerMessage(cohortId, prompt);

        chat.stream(PromptType.PROGRAM_COMPOSER, userMessage, COMPOSER_MAX_TOKENS,
                new StreamingChatPort.StreamHandler() {

                    @Override
                    public void onToken(String token) {
                        if (drafting.compareAndSet(false, true)) {
                            emit(emitter, "status", "Drafting tasks and form fields…");
                        }
                    }

                    @Override
                    public void onComplete(String fullText) {
                        try {
                            emit(emitter, "status", "Calibrating due dates to the drip schedule…");
                            RawDraft raw = draftMapper.readValue(extractJson(fullText), RawDraft.class);
                            emit(emitter, "draft", draftMapper.writeValueAsString(toDraft(raw)));
                            emitter.complete();
                        } catch (Exception e) {
                            log.warn("Composer draft parse failed: {}", e.toString());
                            fail(emitter, "The composer returned an unreadable draft. Try again.");
                        }
                    }

                    @Override
                    public void onError(Throwable error) {
                        fail(emitter, "The AI service is unavailable right now. Try again in a moment.");
                    }
                });
        return emitter;
    }

    private String composerMessage(UUID cohortId, String prompt) {
        String stage = settings.findById(cohortId)
                .map(s -> s.getStageLabel()).orElse("Week");
        StringBuilder curriculum = new StringBuilder();
        List<ProgramModule> existing = modules.findByCohortIdOrderByPositionAsc(cohortId);
        if (existing.isEmpty()) {
            curriculum.append("(no modules yet — this will be the first)");
        }
        for (int i = 0; i < existing.size(); i++) {
            ProgramModule m = existing.get(i);
            curriculum.append(stage).append(' ').append(String.format("%02d", i + 1))
                    .append(" \"").append(m.getName()).append("\": ")
                    .append(m.getTasks().stream().map(ProgramTask::getName)
                            .reduce((a, b) -> a + ", " + b).orElse("(no tasks)"))
                    .append('\n');
        }

        return """
                Existing curriculum (one %s per stage):
                %s
                Program director's brief: %s

                Draft the NEXT module. Respond with ONE JSON object, exactly this shape:
                {"name": "<module name, max 60 chars>",
                 "summary": "<one sentence>",
                 "tasks": [2 to 4 of {"name": "<task name>", "dueInDays": <int 2-14, spread across the stage>,
                   "fields": [3 to 6 of {"type": "<INSTRUCTIONS|VIDEO|MCQ|SHORT|LONG|FILE|CHECKLIST|RATING>",
                     "required": <boolean, always false for INSTRUCTIONS and VIDEO>,
                     "config": <per type — INSTRUCTIONS: {"text": "..."} · VIDEO: {"title": "...", "url": ""} ·
                       MCQ: {"question": "...", "multi": false, "options": ["...", "...", "..."]} ·
                       SHORT: {"question": "...", "placeholder": "..."} · LONG: {"question": "...", "placeholder": "..."} ·
                       FILE: {"question": "...", "accept": ".pdf,.doc,.docx"} ·
                       CHECKLIST: {"question": "...", "items": ["...", "..."]} · RATING: {"question": "...", "scale": 5}>}]}]}
                Start most tasks with an INSTRUCTIONS field. Avoid duplicating existing task names.
                """.formatted(stage.toLowerCase(), curriculum, prompt);
    }

    private ModuleDraft toDraft(RawDraft raw) {
        LocalDate today = LocalDate.now();
        List<ModuleDraft.DraftTask> draftTasks = raw.tasks().stream().map(t -> new ModuleDraft.DraftTask(
                t.name(),
                today.plusDays(t.dueInDays() == null ? 7 : Math.max(1, t.dueInDays())),
                t.fields().stream().map(f -> new ModuleDraft.DraftField(
                        f.type(),
                        f.type().answerable() && f.required(),
                        f.config() == null ? java.util.Map.of() : f.config())).toList())).toList();
        return new ModuleDraft(raw.name(), raw.summary(), draftTasks);
    }

    /** The model's own output shape (relative due dates). */
    private record RawDraft(String name, String summary, List<RawTask> tasks) {
        private record RawTask(String name, Integer dueInDays, List<RawField> fields) {
        }

        private record RawField(com.bvisionry.programflow.domain.FieldType type, boolean required,
                java.util.Map<String, Object> config) {
        }
    }

    // ------------------------------------------------------------------ coach

    public SseEmitter coach(UUID taskId, UUID fieldId, String draft) {
        // Same access rule as the player (org + LIVE + audience + drip): reusing it
        // stops the coach hinting on tasks the learner cannot open, and running
        // inside this method's transaction lets the lazy fields load.
        ProgramTask task = myProgram.requirePlayableTask(taskId);
        ProgramTaskField field = task.getFields().stream()
                .filter(f -> f.getId().equals(fieldId))
                .findFirst()
                .orElseThrow(() -> new ResourceNotFoundException("Field", fieldId.toString()));

        Object question = field.getConfig().getOrDefault("question",
                field.getConfig().getOrDefault("text", field.getConfig().getOrDefault("title", "")));
        String userMessage = """
                Module: %s
                Task: %s
                Step type: %s
                Step: %s
                %s
                Give the hint now.
                """.formatted(
                task.getModule().getName(),
                task.getName(),
                field.getFieldType().name().toLowerCase(),
                question,
                draft == null || draft.isBlank() ? "" : "The learner's current draft answer: " + draft);

        SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MS);
        chat.stream(PromptType.PROGRAM_COACH, userMessage, COACH_MAX_TOKENS,
                new StreamingChatPort.StreamHandler() {

                    @Override
                    public void onToken(String token) {
                        emit(emitter, "token", token);
                    }

                    @Override
                    public void onComplete(String fullText) {
                        emit(emitter, "done", "");
                        emitter.complete();
                    }

                    @Override
                    public void onError(Throwable error) {
                        fail(emitter, "The AI coach is unavailable right now.");
                    }
                });
        return emitter;
    }

    // ---------------------------------------------------------------- helpers

    /** The model may wrap JSON in fences or prose; take the outermost object. */
    private static String extractJson(String text) {
        int start = text.indexOf('{');
        int end = text.lastIndexOf('}');
        if (start < 0 || end <= start) {
            throw new IllegalArgumentException("No JSON object in model output");
        }
        return text.substring(start, end + 1);
    }

    private static void emit(SseEmitter emitter, String event, String data) {
        try {
            emitter.send(SseEmitter.event().name(event).data(data));
        } catch (IOException | IllegalStateException e) {
            // client went away — nothing to do, the emitter is already dead
        }
    }

    private static void fail(SseEmitter emitter, String message) {
        emit(emitter, "error", message);
        emitter.complete();
    }
}
