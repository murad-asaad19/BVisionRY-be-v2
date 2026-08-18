package com.bvisionry.config;

import com.bvisionry.aiconfig.repository.PromptTemplateRepository;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/**
 * Reports prompt templates that still carry the hand-written JSON response
 * contract V194 removed.
 *
 * <p>V194 took the {@code "Respond with ONLY a JSON object: {...}"} block out of
 * the narrative prompts, because it made the parse contract something a super
 * admin could silently break from the AI config screen while LangChain4j
 * derives the real contract from the AiService return type anyway.
 *
 * <p>It could not finish the job, and that is the point of this class. V194
 * skipped every template with a row in {@code prompt_template_revisions} — an
 * edited prompt belongs to the admin who edited it, and rewriting their wording
 * from a migration is not a trade worth making. So on any environment where
 * someone had touched one of these three prompts, the editable contract is
 * still there, the migration reports success, and nothing else ever mentions
 * it. That is silent, permanent drift by design; the only missing piece was
 * someone saying so out loud.
 *
 * <p>A WARN, never a failure: a stale contract still generates correctly today
 * — the guardrail validates and repairs on top of it. What it costs is the
 * protection V194 bought, and the fix is a human editing the prompt in the AI
 * config screen, which is not something a boot sequence can or should do. For
 * the same reason there is no follow-up migration: {@code V*.sql} is
 * append-only, and a V195 that rewrote admin-edited prompts would discard
 * exactly the wording V194 deliberately preserved.
 */
@Slf4j
@Component
public class PromptContractDriftWarning {

    /** The sentence V194 deletes. Matching it is matching the whole block. */
    private static final String CONTRACT_MARKER = "Respond with ONLY a JSON object:";

    private final PromptTemplateRepository templates;

    public PromptContractDriftWarning(PromptTemplateRepository templates) {
        this.templates = templates;
    }

    @EventListener(ApplicationReadyEvent.class)
    @Transactional(readOnly = true)
    public void reportStaleContracts() {
        // A handful of rows, read once at boot — cheaper than a derived query
        // whose LIKE pattern is a second place for the marker to drift.
        List<String> stale = templates.findAll().stream()
                .filter(t -> t.getContent() != null && t.getContent().contains(CONTRACT_MARKER))
                .map(t -> t.getPromptType().name())
                .sorted()
                .toList();

        if (stale.isEmpty()) {
            return;
        }
        log.warn("Prompt contract drift: {} template(s) still embed the JSON response contract "
                        + "that V194 removed — {}. The response shape is therefore editable from the "
                        + "AI config screen, where renaming a key silently breaks every generation for "
                        + "that prompt type. V194 skips admin-edited templates on purpose, so this "
                        + "clears only when someone removes the \"{}\" block by hand.",
                stale.size(), String.join(", ", stale), CONTRACT_MARKER);
    }
}
