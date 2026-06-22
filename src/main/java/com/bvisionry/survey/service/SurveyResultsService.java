package com.bvisionry.survey.service;

import com.bvisionry.common.enums.SubmissionStatus;
import com.bvisionry.common.excel.ExcelWorkbookBuilder;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.survey.dto.PillarSummaryDto;
import com.bvisionry.survey.dto.QuestionSummaryDto;
import com.bvisionry.survey.dto.QuestionSummaryDto.GeoPointDto;
import com.bvisionry.survey.dto.QuestionSummaryDto.HistogramBucketDto;
import com.bvisionry.survey.dto.QuestionSummaryDto.SnippetDto;
import com.bvisionry.survey.dto.SurveyAnswerDetailDto;
import com.bvisionry.survey.dto.SurveyResponseDetailDto;
import com.bvisionry.survey.dto.SurveyResponseListItemDto;
import com.bvisionry.survey.dto.SurveyResponsePageDto;
import com.bvisionry.survey.dto.SurveyResultsSummaryDto;
import com.bvisionry.survey.entity.Survey;
import com.bvisionry.survey.entity.SurveyAnswer;
import com.bvisionry.survey.entity.SurveyPillar;
import com.bvisionry.survey.entity.SurveyQuestion;
import com.bvisionry.survey.entity.SurveyQuestionType;
import com.bvisionry.survey.entity.SurveyResponse;
import com.bvisionry.survey.repository.SurveyAnswerRepository;
import com.bvisionry.survey.repository.SurveyResponseRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SurveyResultsService {

    private static final int MAX_SNIPPETS = 50;
    private static final int SNIPPET_MAX_CHARS = 240;

    private final SurveyService surveyService;
    private final SurveyResponseRepository responseRepository;
    private final SurveyAnswerRepository answerRepository;
    private final CountryCatalog countryCatalog;

    @Transactional(readOnly = true)
    public SurveyResultsSummaryDto getSummary(UUID surveyId) {
        return buildSummary(surveyId, false);
    }

    /**
     * Aggregate for the results "Live" page — same shape as the summary but
     * restricted to sections flagged {@code liveAnalyticsEnabled}. COUNTRY
     * questions carry their per-ISO {@code geoPoints} so the client can render
     * the map. Lean enough to poll every few seconds.
     */
    @Transactional(readOnly = true)
    public SurveyResultsSummaryDto getLive(UUID surveyId) {
        return buildSummary(surveyId, true);
    }

    /**
     * Build the per-section / per-question aggregate. When {@code liveOnly} is
     * set, only questions opted into live analytics are included, and sections
     * left with no such question are dropped.
     */
    private SurveyResultsSummaryDto buildSummary(UUID surveyId, boolean liveOnly) {
        Survey survey = surveyService.findOrThrow(surveyId);
        long total = responseRepository.countBySurveyId(surveyId);

        Object[] firstLastRaw = responseRepository.findFirstAndLastSubmittedAt(surveyId);
        Instant first = null;
        Instant last = null;
        if (firstLastRaw != null && firstLastRaw.length == 2) {
            first = (Instant) firstLastRaw[0];
            last = (Instant) firstLastRaw[1];
        }

        // Scope the answer load to what we'll actually summarize: the live page
        // only renders live-enabled questions, so loading every answer just to
        // discard most on a ~5s poll is wasteful. The query-side filter keeps
        // cost proportional to live questions rather than total responses.
        List<SurveyAnswer> allAnswers = liveOnly
                ? answerRepository.findLiveByResponseSurveyId(surveyId)
                : answerRepository.findByResponseSurveyId(surveyId);
        Map<UUID, List<SurveyAnswer>> answersByQuestion = new HashMap<>();
        for (SurveyAnswer a : allAnswers) {
            answersByQuestion.computeIfAbsent(a.getQuestion().getId(), k -> new ArrayList<>()).add(a);
        }

        List<PillarSummaryDto> pillarSummaries = new ArrayList<>();
        List<SurveyPillar> sortedPillars = new ArrayList<>(survey.getPillars());
        sortedPillars.sort(Comparator.comparingInt(SurveyPillar::getDisplayOrder));
        for (SurveyPillar pillar : sortedPillars) {
            List<QuestionSummaryDto> qs = new ArrayList<>();
            List<SurveyQuestion> sortedQs = new ArrayList<>(pillar.getQuestions());
            sortedQs.sort(Comparator.comparingInt(SurveyQuestion::getDisplayOrder));
            for (SurveyQuestion q : sortedQs) {
                if (liveOnly && !q.isLiveAnalyticsEnabled()) continue;
                qs.add(summarizeQuestion(q, answersByQuestion.getOrDefault(q.getId(), List.of())));
            }
            // On the live page, a section with no opted-in question contributes
            // nothing — drop it so the client renders no empty section card.
            if (liveOnly && qs.isEmpty()) continue;
            pillarSummaries.add(new PillarSummaryDto(
                    pillar.getId(), pillar.getName(), pillar.getDescription(),
                    pillar.getDisplayOrder(), qs));
        }

        return new SurveyResultsSummaryDto(
                survey.getId(), survey.getName(), total, first, last, pillarSummaries);
    }

    private QuestionSummaryDto summarizeQuestion(SurveyQuestion q, List<SurveyAnswer> answers) {
        // Count only rows that actually carry an answer for this question's type.
        // An OPTIONAL question persists a blank/empty answer row when skipped;
        // counting those would inflate the response count and, for choice/country
        // questions, the percentage denominator the client derives from it.
        long count = answers.stream().filter(a -> answerHasContent(q, a)).count();
        Map<String, Long> counts = null;
        BigDecimal average = null;
        BigDecimal min = null;
        BigDecimal max = null;
        List<HistogramBucketDto> histogramBuckets = null;
        List<SnippetDto> snippets = null;
        List<GeoPointDto> geoPoints = null;

        // COUNTRY tabulates a single tally by ISO code, descending — the name-keyed
        // counts (summary bars) and the code-keyed geo points (live map) are both
        // derived from it, so the work is done once.
        Map<String, Long> countryByCode = q.getType() == SurveyQuestionType.COUNTRY
                ? tallyCountriesByCode(answers)
                : null;

        switch (q.getType()) {
            case MULTIPLE_CHOICE -> {
                counts = new LinkedHashMap<>();
                putOptionKeys(q, counts);
                for (SurveyAnswer a : answers) {
                    if (a.getSelectedValues() != null) {
                        for (String v : a.getSelectedValues()) {
                            counts.merge(v, 1L, Long::sum);
                        }
                    } else if (a.getSelectedValue() != null) {
                        counts.merge(a.getSelectedValue(), 1L, Long::sum);
                    }
                }
            }
            case LIKERT -> {
                List<BigDecimal> values = new ArrayList<>();
                for (SurveyAnswer a : answers) {
                    if (a.getNumericValue() != null) values.add(a.getNumericValue());
                }
                if (!values.isEmpty()) {
                    BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    average = sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
                    min = values.stream().min(BigDecimal::compareTo).orElse(null);
                    max = values.stream().max(BigDecimal::compareTo).orElse(null);
                    histogramBuckets = buildLikertHistogram(q, values);
                }
            }
            case NUMBER, SELF_RATING -> {
                List<BigDecimal> values = new ArrayList<>();
                for (SurveyAnswer a : answers) {
                    if (a.getNumericValue() != null) values.add(a.getNumericValue());
                }
                if (!values.isEmpty()) {
                    BigDecimal sum = values.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
                    average = sum.divide(BigDecimal.valueOf(values.size()), 2, RoundingMode.HALF_UP);
                    min = values.stream().min(BigDecimal::compareTo).orElse(null);
                    max = values.stream().max(BigDecimal::compareTo).orElse(null);
                    // Distribution donut (mirrors LIKERT): SELF_RATING uses fixed 0–100
                    // bands; NUMBER uses dynamic equal-width ranges over the observed span.
                    histogramBuckets = q.getType() == SurveyQuestionType.SELF_RATING
                            ? buildSelfRatingHistogram(values)
                            : buildNumberHistogram(values);
                }
            }
            case SHORT_TEXT -> {
                List<SurveyAnswer> sorted = new ArrayList<>(answers);
                sorted.sort(Comparator.comparing(
                        (SurveyAnswer a) -> a.getResponse().getSubmittedAt()).reversed());
                snippets = sorted.stream()
                        .filter(a -> a.getResponseText() != null && !a.getResponseText().isBlank())
                        .limit(MAX_SNIPPETS)
                        .map(a -> {
                            String t = a.getResponseText();
                            String trimmed = t.length() > SNIPPET_MAX_CHARS
                                    ? t.substring(0, SNIPPET_MAX_CHARS) + "…"
                                    : t;
                            return new SnippetDto(trimmed, a.getResponse().getSubmittedAt());
                        })
                        .toList();
            }
            case COUNTRY -> {
                counts = countryCounts(countryByCode);
                geoPoints = countryGeoPoints(countryByCode);
            }
        }

        return new QuestionSummaryDto(
                q.getId(), q.getType(), q.getPromptText(),
                count, counts, average, min, max, histogramBuckets, snippets, geoPoints);
    }

    /**
     * Single source-of-truth tally of COUNTRY answers by ISO-3166 alpha-2 code,
     * ordered most frequent first. Both the name-keyed summary counts and the
     * code-keyed geo points are derived from this, so the tally + sort runs once.
     */
    private Map<String, Long> tallyCountriesByCode(List<SurveyAnswer> answers) {
        Map<String, Long> byCode = new HashMap<>();
        for (SurveyAnswer a : answers) {
            String code = a.getSelectedValue();
            if (code != null && !code.isBlank()) {
                byCode.merge(code.toUpperCase(Locale.ROOT), 1L, Long::sum);
            }
        }
        return byCode.entrySet().stream()
                .sorted(Map.Entry.<String, Long>comparingByValue().reversed())
                .collect(Collectors.toMap(Map.Entry::getKey, Map.Entry::getValue,
                        (x, y) -> x, LinkedHashMap::new));
    }

    /**
     * Project the code-keyed tally onto canonical country names so the summary
     * tab's choice bars read naturally (e.g. "Türkiye"). Iteration order is the
     * tally's descending order. Names come from {@link CountryCatalog} (mirrors
     * the client list), never the JVM {@link Locale}.
     */
    private Map<String, Long> countryCounts(Map<String, Long> byCode) {
        Map<String, Long> out = new LinkedHashMap<>();
        for (Map.Entry<String, Long> e : byCode.entrySet()) {
            out.merge(countryCatalog.displayName(e.getKey()), e.getValue(), Long::sum);
        }
        return out;
    }

    /**
     * Project the code-keyed tally into geo points (most frequent first) for the
     * live map. The client resolves each code to a centroid + name.
     */
    private List<GeoPointDto> countryGeoPoints(Map<String, Long> byCode) {
        return byCode.entrySet().stream()
                .map(e -> new GeoPointDto(e.getKey(), e.getValue()))
                .toList();
    }

    /** Whether an answer row actually carries content for its question's type. */
    private boolean answerHasContent(SurveyQuestion q, SurveyAnswer a) {
        return switch (q.getType()) {
            case SHORT_TEXT -> a.getResponseText() != null && !a.getResponseText().isBlank();
            case LIKERT, NUMBER, SELF_RATING -> a.getNumericValue() != null;
            case MULTIPLE_CHOICE -> (a.getSelectedValues() != null && !a.getSelectedValues().isEmpty())
                    || (a.getSelectedValue() != null && !a.getSelectedValue().isBlank());
            case COUNTRY -> a.getSelectedValue() != null && !a.getSelectedValue().isBlank();
        };
    }

    private void putOptionKeys(SurveyQuestion q, Map<String, Long> counts) {
        if (q.getConfigJson() == null) return;
        Object raw = q.getConfigJson().get("options");
        if (raw instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof String s) counts.put(s, 0L);
            }
        }
    }

    private List<HistogramBucketDto> buildLikertHistogram(SurveyQuestion q, List<BigDecimal> values) {
        List<String> labels = likertLabels(q);
        Map<Integer, Long> buckets = new TreeMap<>();
        for (int i = 1; i <= 5; i++) buckets.put(i, 0L);
        for (BigDecimal v : values) {
            int rounded = v.setScale(0, RoundingMode.HALF_UP).intValue();
            rounded = Math.max(1, Math.min(5, rounded));
            buckets.merge(rounded, 1L, Long::sum);
        }
        List<HistogramBucketDto> out = new ArrayList<>();
        for (var entry : buckets.entrySet()) {
            int position = entry.getKey();
            String label;
            if (labels != null && labels.size() >= position && labels.get(position - 1) != null) {
                label = position + " — " + labels.get(position - 1);
            } else {
                label = String.valueOf(position);
            }
            out.add(new HistogramBucketDto(label, entry.getValue()));
        }
        return out;
    }

    /** Fixed 0–100 bands for the SELF_RATING slider — always all five, in scale order. */
    private static final String[] SELF_RATING_BANDS = {
            "0–20", "21–40", "41–60", "61–80", "81–100"
    };

    private List<HistogramBucketDto> buildSelfRatingHistogram(List<BigDecimal> values) {
        long[] counts = new long[SELF_RATING_BANDS.length];
        for (BigDecimal v : values) {
            int n = Math.max(0, Math.min(100, v.setScale(0, RoundingMode.HALF_UP).intValue()));
            int idx = n <= 20 ? 0 : n <= 40 ? 1 : n <= 60 ? 2 : n <= 80 ? 3 : 4;
            counts[idx]++;
        }
        List<HistogramBucketDto> out = new ArrayList<>(SELF_RATING_BANDS.length);
        for (int i = 0; i < SELF_RATING_BANDS.length; i++) {
            out.add(new HistogramBucketDto(SELF_RATING_BANDS[i], counts[i]));
        }
        return out;
    }

    /**
     * Equal-width histogram over the observed integer range of NUMBER answers
     * (values rounded to whole numbers). One bucket when every value is equal;
     * otherwise up to five contiguous ranges spanning min→max. Single-width
     * buckets are labelled with the bare value rather than "n–n".
     */
    private List<HistogramBucketDto> buildNumberHistogram(List<BigDecimal> values) {
        List<Integer> ints = values.stream()
                .map(v -> v.setScale(0, RoundingMode.HALF_UP).intValue())
                .sorted()
                .toList();
        int minV = ints.get(0);
        int maxV = ints.get(ints.size() - 1);
        if (minV == maxV) {
            return List.of(new HistogramBucketDto(String.valueOf(minV), (long) ints.size()));
        }
        long span = (long) maxV - minV + 1;
        int bucketCount = (int) Math.min(5, span);
        int width = (int) Math.ceil((double) span / bucketCount);
        List<HistogramBucketDto> out = new ArrayList<>(bucketCount);
        for (int b = 0; b < bucketCount; b++) {
            final int lo = minV + b * width;
            if (lo > maxV) break;
            final int hi = Math.min(maxV, lo + width - 1);
            String label = lo == hi ? String.valueOf(lo) : lo + "–" + hi;
            long c = ints.stream().filter(n -> n >= lo && n <= hi).count();
            out.add(new HistogramBucketDto(label, c));
        }
        return out;
    }

    private List<String> likertLabels(SurveyQuestion q) {
        if (q.getConfigJson() == null) return null;
        Object raw = q.getConfigJson().get("labels");
        if (raw instanceof List<?> list) {
            List<String> out = new ArrayList<>();
            for (Object o : list) {
                out.add(o instanceof String s ? s : null);
            }
            return out;
        }
        return null;
    }

    @Transactional(readOnly = true)
    public SurveyResponsePageDto listResponses(UUID surveyId, String q, Instant from, Instant to,
                                                int page, int size) {
        Survey survey = surveyService.findOrThrow(surveyId);
        // Lowercased + LIKE-wrapped search term, or null for "no search". Pushed
        // into the repository so we don't pull the full response set into memory.
        String qLower = (q == null || q.isBlank()) ? null : "%" + q.toLowerCase() + "%";

        Page<SurveyResponse> pageResult = responseRepository.findFiltered(
                surveyId, qLower, from, to, PageRequest.of(page, size));

        // Duplicate flagging needs to consider the entire filtered set, not just
        // the current page — pull just the dedup keys that occur >1 across the
        // same filter and use them to flag rows on the current page.
        Set<String> duplicateKeys = new HashSet<>();
        for (String cookieId : responseRepository.findDuplicateCookieIds(surveyId, qLower, from, to)) {
            duplicateKeys.add("cookie:" + cookieId);
        }
        for (String ipHash : responseRepository.findDuplicateIpHashes(surveyId, qLower, from, to)) {
            duplicateKeys.add("ip:" + ipHash);
        }

        // Gift-assessment status: each response carries a persisted link to the
        // submission made from its gift email (survey_responses.submission_id),
        // so resolve it directly by response id — scoped to this survey's gift
        // link — rather than guessing by shared email. One query per page.
        UUID giftLinkId = survey.getGiftPublicAssessmentLinkId();
        Map<UUID, Object[]> giftRefsByResponse = Map.of();
        if (giftLinkId != null) {
            List<UUID> responseIds = pageResult.getContent().stream()
                    .map(SurveyResponse::getId).toList();
            if (!responseIds.isEmpty()) {
                giftRefsByResponse = responseRepository
                        .findGiftSubmissionRefs(responseIds, giftLinkId).stream()
                        .collect(Collectors.toMap(row -> (UUID) row[0], row -> row, (a, b) -> a));
            }
        }
        final Map<UUID, Object[]> giftRefs = giftRefsByResponse;

        List<SurveyResponseListItemDto> items = pageResult.getContent().stream()
                .map(r -> {
                    String key = dedupKey(r);
                    Object[] gift = giftRefs.get(r.getId());
                    return new SurveyResponseListItemDto(
                            r.getId(),
                            r.getSubmittedAt(),
                            r.getSource(),
                            r.getRespondentEmail(),
                            r.getRespondentName(),
                            key != null && duplicateKeys.contains(key),
                            gift == null ? null : (SubmissionStatus) gift[2],
                            gift == null ? null : (UUID) gift[1]);
                })
                .toList();

        return new SurveyResponsePageDto(
                items, page, size, pageResult.getTotalElements(), pageResult.getTotalPages(),
                giftLinkId);
    }

    private String dedupKey(SurveyResponse r) {
        if (r.getCookieId() != null) return "cookie:" + r.getCookieId();
        if (r.getIpHash() != null) return "ip:" + r.getIpHash();
        return null;
    }

    @Transactional(readOnly = true)
    public SurveyResponseDetailDto getResponseDetail(UUID surveyId, UUID responseId) {
        SurveyResponse r = responseRepository.findByIdAndSurveyId(responseId, surveyId)
                .orElseThrow(() -> new ResourceNotFoundException("SurveyResponse", responseId.toString()));

        return new SurveyResponseDetailDto(
                r.getId(), r.getSubmittedAt(), r.getSource(),
                r.getRespondentEmail(), r.getRespondentName(),
                buildAnswerDetailsForResponse(responseId));
    }

    /**
     * Build the ordered list of answer detail rows for a single response,
     * sorted by pillar then question display order. Shared by the survey
     * response-detail view and the member-results view (which embeds the
     * post-assessment survey response next to the assessment evaluation).
     */
    @Transactional(readOnly = true)
    public List<SurveyAnswerDetailDto> buildAnswerDetailsForResponse(UUID responseId) {
        Comparator<SurveyAnswer> byPillarThenQuestion = Comparator
                .comparingInt((SurveyAnswer a) -> a.getQuestion().getPillar().getDisplayOrder())
                .thenComparingInt(a -> a.getQuestion().getDisplayOrder());

        return answerRepository.findByResponseId(responseId).stream()
                .sorted(byPillarThenQuestion)
                .map(a -> {
                    SurveyQuestion q = a.getQuestion();
                    List<String> likertLabels = null;
                    if (q.getType() == SurveyQuestionType.LIKERT) {
                        likertLabels = likertLabels(q);
                    }
                    return new SurveyAnswerDetailDto(
                            q.getId(),
                            q.getPillar().getId(),
                            q.getPillar().getName(),
                            q.getPromptText(),
                            q.getType(),
                            a.getResponseText(),
                            a.getSelectedValue(),
                            a.getSelectedValues(),
                            a.getNumericValue(),
                            likertLabels);
                })
                .toList();
    }

    @Transactional(readOnly = true)
    public void writeXlsx(UUID surveyId, OutputStream out) throws IOException {
        Survey survey = surveyService.findOrThrow(surveyId);

        List<SurveyPillar> pillars = new ArrayList<>(survey.getPillars());
        pillars.sort(Comparator.comparingInt(SurveyPillar::getDisplayOrder));

        List<SurveyQuestion> orderedQuestions = new ArrayList<>();
        for (SurveyPillar p : pillars) {
            List<SurveyQuestion> sorted = new ArrayList<>(p.getQuestions());
            sorted.sort(Comparator.comparingInt(SurveyQuestion::getDisplayOrder));
            orderedQuestions.addAll(sorted);
        }

        List<SurveyResponse> responses = responseRepository.findBySurveyIdOrderBySubmittedAtDesc(surveyId);
        Map<UUID, Map<UUID, SurveyAnswer>> answersByResponse = new HashMap<>();
        Map<UUID, List<SurveyAnswer>> answersByQuestion = new HashMap<>();
        for (SurveyAnswer a : answerRepository.findByResponseSurveyId(surveyId)) {
            answersByResponse
                    .computeIfAbsent(a.getResponse().getId(), k -> new HashMap<>())
                    .put(a.getQuestion().getId(), a);
            answersByQuestion
                    .computeIfAbsent(a.getQuestion().getId(), k -> new ArrayList<>())
                    .add(a);
        }

        try (ExcelWorkbookBuilder wb = new ExcelWorkbookBuilder()) {
            ExcelWorkbookBuilder.SheetBuilder responsesSheet = wb.newSheet("Responses");
            List<String> header = new ArrayList<>();
            header.add("Submitted at");
            header.add("Source");
            header.add("Respondent email");
            header.add("Respondent name");
            for (SurveyQuestion q : orderedQuestions) {
                header.add(columnLabel(q.getPillar().getName(), q.getPromptText()));
            }
            responsesSheet.headers(header.toArray(new String[0]));

            for (SurveyResponse r : responses) {
                Map<UUID, SurveyAnswer> answersByQ = answersByResponse.getOrDefault(r.getId(), Map.of());
                List<Object> row = new ArrayList<>();
                row.add(r.getSubmittedAt());
                row.add(r.getSource());
                row.add(Optional.ofNullable(r.getRespondentEmail()).orElse(""));
                row.add(Optional.ofNullable(r.getRespondentName()).orElse(""));
                for (SurveyQuestion q : orderedQuestions) {
                    row.add(formatAnswerCell(q, answersByQ.get(q.getId())));
                }
                responsesSheet.row(row.toArray());
            }
            responsesSheet.autoSize();

            writeOverviewSheet(wb, survey, pillars, responses, answersByResponse);
            writeQuestionsSheet(wb, pillars, responses, answersByQuestion);

            wb.write(out);
        }
    }

    private void writeOverviewSheet(ExcelWorkbookBuilder wb, Survey survey, List<SurveyPillar> pillars,
                                     List<SurveyResponse> responses,
                                     Map<UUID, Map<UUID, SurveyAnswer>> answersByResponse) {
        ExcelWorkbookBuilder.SheetBuilder sheet = wb.newSheet("Overview");
        sheet.headers("Field", "Value");

        sheet.labeledRow("Survey name", survey.getName());
        sheet.labeledRow("Status", ExcelWorkbookBuilder.humanize(survey.getStatus().name()));
        sheet.labeledRow("Total responses", responses.size());

        Instant first = responses.stream()
                .map(SurveyResponse::getSubmittedAt).filter(Objects::nonNull)
                .min(Instant::compareTo).orElse(null);
        Instant last = responses.stream()
                .map(SurveyResponse::getSubmittedAt).filter(Objects::nonNull)
                .max(Instant::compareTo).orElse(null);
        sheet.labeledRow("First submitted", first);
        sheet.labeledRow("Last submitted", last);

        Map<String, Long> bySource = new LinkedHashMap<>();
        for (SurveyResponse r : responses) {
            bySource.merge(r.getSource().name(), 1L, Long::sum);
        }
        for (Map.Entry<String, Long> e : bySource.entrySet()) {
            sheet.labeledRow("via " + ExcelWorkbookBuilder.humanize(e.getKey()), e.getValue());
        }

        long uniqueRespondents = responses.stream()
                .map(this::dedupKey).filter(Objects::nonNull).distinct().count();
        sheet.labeledRow("Unique respondents (by cookie/IP)", uniqueRespondents);

        int totalQuestions = pillars.stream()
                .mapToInt(p -> p.getQuestions().size()).sum();
        if (totalQuestions > 0 && !responses.isEmpty()) {
            double totalAnswered = 0;
            for (Map<UUID, SurveyAnswer> answers : answersByResponse.values()) {
                totalAnswered += answers.size();
            }
            double avgAnsweredPerResponse = totalAnswered / responses.size();
            String label = String.format(
                    "%.1f / %d (%d%%)",
                    avgAnsweredPerResponse,
                    totalQuestions,
                    Math.round(100.0 * avgAnsweredPerResponse / totalQuestions));
            sheet.labeledRow("Avg questions answered", label);
        }

        sheet.autoSize();
    }

    private void writeQuestionsSheet(ExcelWorkbookBuilder wb, List<SurveyPillar> pillars,
                                      List<SurveyResponse> responses,
                                      Map<UUID, List<SurveyAnswer>> answersByQuestion) {
        ExcelWorkbookBuilder.SheetBuilder sheet = wb.newSheet("Questions");
        sheet.headers("Pillar", "Question", "Type", "Required",
                "Responses", "Answered %", "Average", "Min", "Max", "Breakdown");

        int totalResponses = responses.size();

        for (SurveyPillar pillar : pillars) {
            List<SurveyQuestion> sortedQs = new ArrayList<>(pillar.getQuestions());
            sortedQs.sort(Comparator.comparingInt(SurveyQuestion::getDisplayOrder));
            for (SurveyQuestion q : sortedQs) {
                List<SurveyAnswer> answers = answersByQuestion.getOrDefault(q.getId(), List.of());
                QuestionSummaryDto dto = summarizeQuestion(q, answers);
                String answeredPct = totalResponses == 0
                        ? ""
                        : Math.round(100.0 * dto.responseCount() / totalResponses) + "%";
                sheet.row(
                        pillar.getName(),
                        q.getPromptText(),
                        ExcelWorkbookBuilder.humanize(dto.type().name()),
                        q.isRequired() ? "Yes" : "No",
                        dto.responseCount(),
                        answeredPct,
                        dto.average(),
                        dto.min(),
                        dto.max(),
                        buildBreakdown(q, dto, answers));
            }
        }
        sheet.autoSize();
    }

    private String buildBreakdown(SurveyQuestion q, QuestionSummaryDto dto, List<SurveyAnswer> answers) {
        // Breakdown only carries unique info for MULTIPLE_CHOICE (option counts) and LIKERT
        // (histogram). For SHORT_TEXT the char-length stats were noise, and for NUMBER /
        // SELF_RATING the Average/Min/Max columns already cover it.
        long total = dto.responseCount();
        return switch (q.getType()) {
            case MULTIPLE_CHOICE, COUNTRY -> formatOptionCountsWithPct(dto.counts(), total);
            case LIKERT -> formatHistogramWithPct(dto.histogramBuckets(), total);
            case SHORT_TEXT, NUMBER, SELF_RATING -> "";
        };
    }

    private String formatOptionCountsWithPct(Map<String, Long> counts, long total) {
        if (counts == null || counts.isEmpty() || total == 0) return "";
        return counts.entrySet().stream()
                .map(e -> e.getKey() + ": " + e.getValue()
                        + " (" + Math.round(100.0 * e.getValue() / total) + "%)")
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
    }

    private String formatHistogramWithPct(List<QuestionSummaryDto.HistogramBucketDto> buckets, long total) {
        if (buckets == null || buckets.isEmpty() || total == 0) return "";
        return buckets.stream()
                .map(b -> b.label() + ": " + b.count()
                        + " (" + Math.round(100.0 * b.count() / total) + "%)")
                .reduce((a, b) -> a + " · " + b)
                .orElse("");
    }

    private String columnLabel(String pillarName, String questionPrompt) {
        String safePillar = pillarName == null ? "" : pillarName;
        return safePillar + " — " + (questionPrompt == null ? "" : questionPrompt);
    }

    private String formatAnswerCell(SurveyQuestion q, SurveyAnswer a) {
        if (a == null) return "";
        return switch (q.getType()) {
            case SHORT_TEXT -> Optional.ofNullable(a.getResponseText()).orElse("");
            case MULTIPLE_CHOICE -> {
                if (a.getSelectedValues() != null && !a.getSelectedValues().isEmpty()) {
                    yield String.join("|", a.getSelectedValues());
                }
                yield Optional.ofNullable(a.getSelectedValue()).orElse("");
            }
            case LIKERT, NUMBER, SELF_RATING -> a.getNumericValue() == null
                    ? Optional.ofNullable(a.getSelectedValue()).orElse("")
                    : a.getNumericValue().toPlainString();
            case COUNTRY -> a.getSelectedValue() == null ? "" : countryCatalog.displayName(a.getSelectedValue());
        };
    }
}
