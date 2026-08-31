package com.bvisionry.exercise;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.common.surveylink.PublicSurveyLinkPort;
import com.bvisionry.exercise.dto.PublicExerciseDto;
import com.bvisionry.exercise.dto.PublicExerciseResponseDto;
import com.bvisionry.exercise.dto.PublicExerciseSubmitRequest;
import com.bvisionry.exercise.entity.ExerciseColumn;
import com.bvisionry.exercise.entity.ExerciseTemplate;
import com.bvisionry.exercise.entity.ExerciseTemplateKind;
import com.bvisionry.exercise.entity.ExerciseTemplateStatus;
import com.bvisionry.exercise.entity.PublicExerciseResponse;
import com.bvisionry.exercise.entity.RespondentFieldMode;
import com.bvisionry.exercise.repository.ExerciseTemplateRepository;
import com.bvisionry.exercise.repository.PublicExerciseResponseRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * The public link side of an exercise: an anonymous respondent opens
 * {@code /exercise/{token}}, fills the sheet or worksheet once, and submits.
 *
 * <p>Deliberately NOT built on {@link ExerciseSubmissionService}: that models a
 * member's working copy — autosaved, reviewed, resubmitted, tied to an
 * assignment inside an organization. A public respondent has no login, no org
 * and no way back, so their fill is a single write-once
 * {@link PublicExerciseResponse}. What the two DO share is what "filled in
 * enough to submit" means ({@link SheetCells} / {@link WorksheetBlocks}), so a
 * required column can never mean two different things.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class PublicExerciseService {

    /**
     * Ceiling on one anonymous sheet submit. Nothing else bounds it: the sheet
     * is append-as-you-go by design and the endpoint takes no credentials, so
     * without a cap one request can write an arbitrarily large document.
     */
    private static final int MAX_ROWS = 200;

    /**
     * Ceiling on the text one anonymous fill may store, in characters. Nothing
     * else bounds it: cells and worksheet answers are free-form JSON written
     * into unbounded {@code jsonb} by an endpoint that takes no credentials, so
     * {@link #MAX_ROWS} caps the row COUNT and this caps their content — the
     * same reason {@code SurveyAnswerSubmitDto} caps every anonymous answer.
     */
    private static final int MAX_CONTENT_CHARS = 200_000;

    /** V210 stores it as VARCHAR(512); a client sets this header, so it is cut to fit. */
    private static final int MAX_USER_AGENT_CHARS = 512;

    private final ExerciseTemplateRepository templateRepository;
    private final PublicExerciseResponseRepository responseRepository;
    /**
     * Resolving the paired survey through the shared kernel, not by reaching
     * into the survey package: features may depend on {@code common}, never on
     * each other. Whether the survey is offerable at all is the port's call.
     */
    private final PublicSurveyLinkPort publicSurveyLinkPort;
    private final MediaUrlPort mediaUrlPort;

    /** What the anonymous taker page renders. */
    @Transactional(readOnly = true)
    public PublicExerciseDto getByToken(UUID token) {
        ExerciseTemplate template = requirePublicTemplate(token);
        return PublicExerciseDto.from(template,
                mediaUrlPort.resolveUrl(template.getCoverImageUrl()),
                resolvePostCompletionSurvey(template));
    }

    /**
     * The paired survey, but only when an anonymous respondent could actually
     * open it — the port answers that. Anything else resolves to no CTA rather
     * than a dead link, while the pairing itself is left alone, so republishing
     * the survey brings the CTA back.
     */
    private PublicExerciseDto.PostCompletionSurvey resolvePostCompletionSurvey(
            ExerciseTemplate template) {
        return publicSurveyLinkPort.publicLink(template.getPostCompletionSurveyId())
                .map(link -> new PublicExerciseDto.PostCompletionSurvey(
                        link.token(), link.name()))
                .orElse(null);
    }

    /**
     * Records one anonymous fill. The token is re-checked here rather than
     * trusted from the page load: an exercise can be taken private between the
     * two calls, and a stale tab must not be able to write past that.
     */
    @Transactional
    public UUID submit(UUID token, PublicExerciseSubmitRequest request,
                       String ipHash, String userAgent) {
        ExerciseTemplate template = requirePublicTemplate(token);
        // Re-checked server-side for the same reason the token is: a stale tab
        // rendered before the admin turned saving off must not be able to write.
        if (!template.isSavePublicResponses()) {
            throw new BadRequestException("This exercise does not collect responses.");
        }

        PublicExerciseResponse response = new PublicExerciseResponse();
        response.setTemplate(template);
        response.setRespondentName(requireRespondentField(
                request.respondentName(), template.getRespondentNameMode(), "name"));
        response.setRespondentEmail(requireRespondentField(
                request.respondentEmail(), template.getRespondentEmailMode(), "email address"));

        if (template.getKind() == ExerciseTemplateKind.WORKSHEET) {
            Map<String, Object> answers =
                    WorksheetBlocks.sanitizeAnswers(request.answers(), template.getBlocks());
            WorksheetBlocks.requireComplete(answers, template.getBlocks());
            requireWithinContentLimit(answers);
            response.setAnswers(answers);
        } else {
            List<Map<String, Object>> rows = sanitizeRows(request.rows(), template);
            SheetCells.requireComplete(rows, template.getColumns());
            requireWithinContentLimit(rows);
            response.setSheetRows(rows);
        }

        response.setIpHash(ipHash);
        response.setUserAgent(userAgent == null || userAgent.length() <= MAX_USER_AGENT_CHARS
                ? userAgent
                : userAgent.substring(0, MAX_USER_AGENT_CHARS));
        response.setSubmittedAt(Instant.now());
        UUID id = responseRepository.save(response).getId();
        log.info("Public exercise response {} recorded for template {}", id, template.getId());
        return id;
    }

    // ------------------------------------------------------------------
    // Admin reads
    // ------------------------------------------------------------------

    @Transactional(readOnly = true)
    public Page<PublicExerciseResponseDto> listResponses(UUID templateId, int page, int size) {
        return responseRepository
                .findByTemplateIdOrderBySubmittedAtDesc(templateId,
                        PageRequest.of(Math.max(page, 0), Math.clamp(size, 1, 100)))
                .map(PublicExerciseResponseDto::summary);
    }

    @Transactional(readOnly = true)
    public PublicExerciseResponseDto getResponse(UUID templateId, UUID responseId) {
        return responseRepository.findByIdAndTemplateId(responseId, templateId)
                .map(PublicExerciseResponseDto::detail)
                .orElseThrow(() -> new ResourceNotFoundException("Response", responseId.toString()));
    }

    // ------------------------------------------------------------------

    /**
     * Both gates in one place. A 404 (not a 403) for every failure: whether a
     * token exists at all is not something an anonymous caller may probe.
     */
    ExerciseTemplate requirePublicTemplate(UUID token) {
        return templateRepository.findByPublicTokenWithColumns(token)
                .filter(ExerciseTemplate::isPublic)
                .filter(t -> t.getStatus() == ExerciseTemplateStatus.PUBLISHED)
                .orElseThrow(() -> new ResourceNotFoundException("Exercise", token.toString()));
    }

    /**
     * Refuses a fill whose stored text exceeds {@link #MAX_CONTENT_CHARS}.
     * Measured over the SANITIZED document, so it bounds exactly what reaches
     * the column rather than what was posted.
     */
    static void requireWithinContentLimit(Object document) {
        if (contentChars(document) > MAX_CONTENT_CHARS) {
            throw new BadRequestException("This response is too long to submit.");
        }
    }

    /** Characters of text in a JSON document, keys included. */
    private static int contentChars(Object value) {
        int total = 0;
        if (value instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                total += contentChars(entry.getKey()) + contentChars(entry.getValue());
            }
        } else if (value instanceof Collection<?> items) {
            for (Object item : items) {
                total += contentChars(item);
            }
        } else if (value != null) {
            total = String.valueOf(value).length();
        }
        return total;
    }

    /** Trims the value, and refuses a blank one when the exercise requires it. */
    private String requireRespondentField(String value, RespondentFieldMode mode, String label) {
        if (mode == RespondentFieldMode.NONE) {
            return null;
        }
        String trimmed = value == null ? "" : value.trim();
        if (trimmed.isEmpty()) {
            if (mode == RespondentFieldMode.REQUIRED) {
                throw new BadRequestException("Your " + label + " is required.");
            }
            return null;
        }
        return trimmed;
    }

    /**
     * Drops unknown columns and blank rows, enforces the row ceiling and the
     * template's own row rules, and restores every locked cell from the starter
     * rows — locked columns are the admin's prefill, so a respondent's value for
     * one is discarded exactly as it is on the member path.
     */
    List<Map<String, Object>> sanitizeRows(List<Map<String, Object>> rows,
                                           ExerciseTemplate template) {
        List<Map<String, Object>> sent = rows == null ? List.of() : rows;
        if (sent.size() > MAX_ROWS) {
            throw new BadRequestException("A public exercise accepts at most " + MAX_ROWS + " rows.");
        }
        List<Map<String, Object>> starterRows = template.getStarterRows() == null
                ? List.of() : template.getStarterRows();
        if (!template.isAllowAddRows() && sent.size() > starterRows.size()) {
            throw new BadRequestException("This exercise does not allow adding rows.");
        }

        Set<String> columnIds = new HashSet<>();
        List<ExerciseColumn> lockedColumns = new ArrayList<>();
        for (ExerciseColumn column : template.getColumns()) {
            columnIds.add(column.getId().toString());
            if (column.isLocked()) {
                lockedColumns.add(column);
            }
        }

        List<Map<String, Object>> clean = new ArrayList<>();
        for (int i = 0; i < sent.size(); i++) {
            Map<String, Object> cells =
                    new LinkedHashMap<>(SheetCells.sanitize(sent.get(i), columnIds));
            Map<String, Object> starter = i < starterRows.size() ? starterRows.get(i) : Map.of();
            for (ExerciseColumn locked : lockedColumns) {
                String key = locked.getId().toString();
                Object prefill = starter == null ? null : starter.get(key);
                if (prefill == null) {
                    cells.remove(key);
                } else {
                    cells.put(key, prefill);
                }
            }
            // A row nobody wrote anything into is noise, not an answer — except
            // a starter row, which the admin put there and which stays in place
            // so the rows keep lining up with what the respondent saw.
            if (!cells.isEmpty() || i < starterRows.size()) {
                clean.add(cells);
            }
        }
        return clean;
    }
}
