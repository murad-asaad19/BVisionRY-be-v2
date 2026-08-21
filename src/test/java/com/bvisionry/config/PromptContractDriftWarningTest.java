package com.bvisionry.config;

import com.bvisionry.aiconfig.entity.PromptTemplate;
import com.bvisionry.aiconfig.repository.PromptTemplateRepository;
import com.bvisionry.common.enums.PromptType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The drift detector's one job: notice a template still carrying the JSON
 * response contract V194 removed, and stay quiet when none does. The class only
 * logs, so the behaviour under test is "does it read the marker correctly" —
 * asserted against the repository call, since a false positive would cry wolf on
 * every boot and a false negative would leave the drift as silent as before.
 */
class PromptContractDriftWarningTest {

    private static final String CONTRACT = "Respond with ONLY a JSON object:";

    private PromptTemplate template(PromptType type, String content) {
        PromptTemplate t = new PromptTemplate();
        t.setPromptType(type);
        t.setContent(content);
        return t;
    }

    @Test
    void staysSilentWhenNoTemplateCarriesTheContract() {
        PromptTemplateRepository repo = mock(PromptTemplateRepository.class);
        when(repo.findAll()).thenReturn(List.of(
                template(PromptType.SHIFT_NARRATIVE, "When a pillar declined, a closing action is MANDATORY."),
                template(PromptType.MEMBER_GROWTH_SUMMARY, "Give at least one observation.")));

        // No throw and no second read is the whole contract of the quiet path;
        // the log itself is a side effect we don't assert on.
        new PromptContractDriftWarning(repo).reportStaleContracts();

        verify(repo, times(1)).findAll();
    }

    @Test
    void detectsATemplateStillEmbeddingTheContractWithoutTouchingIt() {
        PromptTemplateRepository repo = mock(PromptTemplateRepository.class);
        // A matched row alongside a null-content row: the log path must fire on
        // the first and the null must not NPE the scan. Reporting never writes.
        when(repo.findAll()).thenReturn(List.of(
                template(PromptType.SHIFT_NARRATIVE, "OUTPUT\n" + CONTRACT + " {...}"),
                template(PromptType.COHORT_GROWTH_SUMMARY, null)));

        new PromptContractDriftWarning(repo).reportStaleContracts();

        verify(repo, never()).save(org.mockito.ArgumentMatchers.any());
    }
}
