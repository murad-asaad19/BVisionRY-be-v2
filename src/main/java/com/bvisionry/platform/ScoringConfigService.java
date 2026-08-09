package com.bvisionry.platform;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.FieldValidationException;
import com.bvisionry.common.scoringconfig.ScoringBands;
import com.bvisionry.platform.dto.ScoringConfigResponse;
import com.bvisionry.platform.dto.ScoringConfigResponse.BandsSection;
import com.bvisionry.platform.dto.ScoringConfigResponse.NarrativeWordingSection;
import com.bvisionry.platform.dto.ScoringConfigResponse.ParticipationCategory;
import com.bvisionry.platform.dto.ScoringConfigResponse.ParticipationFormulaSection;
import com.bvisionry.platform.dto.ScoringConfigResponse.QualityTag;
import com.bvisionry.platform.dto.ScoringConfigResponse.QualityTagsSection;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;

/**
 * The "Scoring &amp; Labels" platform config (spec §7): one JSON document per
 * section in {@code platform_settings}, SUPER_ADMIN-only writes, shipped
 * defaults when a row is absent. Stable keys ({@code band_1}, {@code decline},
 * category keys) are identity; labels are presentation. Structural validation
 * failures surface as {@code fieldErrors} via {@link FieldValidationException}.
 *
 * <p>Consumers snapshot at computation time (the comparison slice stamps the
 * shift bands it used into {@code config_snapshot}); edits here apply forward
 * only.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ScoringConfigService {

    static final String FORMULA_KEY = "scoring.participation_formula";
    static final String PARTICIPATION_BANDS_KEY = "scoring.participation_bands";
    static final String QUALITY_TAGS_KEY = "scoring.quality_tags";
    static final String NARRATIVE_KEY = "scoring.narrative_wording";

    /** The protected, always-computed category (spec §4/§7 amended). */
    static final String ASSIGNMENTS_KEY = "assignments";

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final PlatformSettingRepository settings;
    private final NamedParameterJdbcTemplate jdbc;
    private final AuditLogger auditLogger;

    /* ------------------------------------------------------------- defaults */

    static List<ParticipationCategory> defaultCategories() {
        return List.of(
                new ParticipationCategory(ASSIGNMENTS_KEY, "Assignments", 50, true),
                new ParticipationCategory("workshops", "Workshops", 25, false),
                new ParticipationCategory("coaching_1on1", "Coaching 1:1", 15, false),
                new ParticipationCategory("coaching_group", "Group coaching", 10, false));
    }

    static List<ScoringBands.Band> defaultParticipationBands() {
        return List.of(
                new ScoringBands.Band("band_1", "High", 80, 100),
                new ScoringBands.Band("band_2", "Partial", 50, 79),
                new ScoringBands.Band("band_3", "Low", 0, 49));
    }

    static List<QualityTag> defaultQualityTags() {
        return List.of(
                new QualityTag("thin", "Thin"),
                new QualityTag("adequate", "Adequate"),
                new QualityTag("strong", "Strong"));
    }

    static String defaultNarrativeSentence() {
        return "There isn't enough before-data to compare this pillar yet.";
    }

    static String defaultDeclineCloseInstruction() {
        return "Every decline must end with a concrete next step — never a verdict.";
    }

    /* ----------------------------------------------------------------- read */

    @Transactional(readOnly = true)
    public ScoringConfigResponse get() {
        return new ScoringConfigResponse(
                section(FORMULA_KEY, CategoriesDoc.class,
                        d -> d.categories(), ScoringConfigService::defaultCategories,
                        (v, at, by) -> new ParticipationFormulaSection(v, at, by)),
                section(PARTICIPATION_BANDS_KEY, BandsDoc.class,
                        d -> d.bands(), ScoringConfigService::defaultParticipationBands,
                        (v, at, by) -> new BandsSection(v, at, by)),
                section(ScoringBands.SHIFT_BANDS_KEY, BandsDoc.class,
                        d -> d.bands(), ScoringBands::defaultShiftBands,
                        (v, at, by) -> new BandsSection(v, at, by)),
                section(QUALITY_TAGS_KEY, TagsDoc.class,
                        d -> d.tags(), ScoringConfigService::defaultQualityTags,
                        (v, at, by) -> new QualityTagsSection(v, at, by)),
                narrativeSection());
    }

    /* ---------------------------------------------------------------- writes */

    @Transactional
    public ParticipationFormulaSection putFormula(List<ParticipationCategory> categories, UUID actorId) {
        validateFormula(categories);
        save(FORMULA_KEY, new CategoriesDoc(categories), actorId);
        return section(FORMULA_KEY, CategoriesDoc.class, CategoriesDoc::categories,
                ScoringConfigService::defaultCategories,
                (v, at, by) -> new ParticipationFormulaSection(v, at, by));
    }

    @Transactional
    public BandsSection putParticipationBands(List<ScoringBands.Band> bands, UUID actorId) {
        throwIfInvalid(ScoringBands.validatePercentBands(bands));
        save(PARTICIPATION_BANDS_KEY, new BandsDoc(bands), actorId);
        return section(PARTICIPATION_BANDS_KEY, BandsDoc.class, BandsDoc::bands,
                ScoringConfigService::defaultParticipationBands,
                (v, at, by) -> new BandsSection(v, at, by));
    }

    @Transactional
    public BandsSection putShiftBands(List<ScoringBands.Band> bands, UUID actorId) {
        throwIfInvalid(ScoringBands.validateShiftBands(bands));
        save(ScoringBands.SHIFT_BANDS_KEY, new BandsDoc(bands), actorId);
        return section(ScoringBands.SHIFT_BANDS_KEY, BandsDoc.class, BandsDoc::bands,
                ScoringBands::defaultShiftBands,
                (v, at, by) -> new BandsSection(v, at, by));
    }

    @Transactional
    public QualityTagsSection putQualityTags(List<QualityTag> tags, UUID actorId) {
        validateQualityTags(tags);
        save(QUALITY_TAGS_KEY, new TagsDoc(tags), actorId);
        return section(QUALITY_TAGS_KEY, TagsDoc.class, TagsDoc::tags,
                ScoringConfigService::defaultQualityTags,
                (v, at, by) -> new QualityTagsSection(v, at, by));
    }

    @Transactional
    public NarrativeWordingSection putNarrativeWording(String sentence, String declineInstruction,
                                                       UUID actorId) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (sentence == null || sentence.isBlank()) {
            errors.put("notEnoughDataSentence", "The sentence must not be blank.");
        }
        if (declineInstruction == null || declineInstruction.isBlank()) {
            errors.put("declineCloseInstruction", "The instruction must not be blank.");
        }
        throwIfInvalid(errors);
        save(NARRATIVE_KEY, new NarrativeDoc(sentence.trim(), declineInstruction.trim()), actorId);
        return narrativeSection();
    }

    /**
     * Per-field default fallback: a doc stored before the decline instruction
     * existed parses with that field null and must not blank the card.
     */
    private NarrativeWordingSection narrativeSection() {
        return section(NARRATIVE_KEY, NarrativeDoc.class, d -> d,
                () -> new NarrativeDoc(defaultNarrativeSentence(), defaultDeclineCloseInstruction()),
                (d, at, by) -> new NarrativeWordingSection(
                        d.notEnoughDataSentence() == null || d.notEnoughDataSentence().isBlank()
                                ? defaultNarrativeSentence() : d.notEnoughDataSentence(),
                        d.declineCloseInstruction() == null || d.declineCloseInstruction().isBlank()
                                ? defaultDeclineCloseInstruction() : d.declineCloseInstruction(),
                        at, by));
    }

    /* ------------------------------------------------------------ validation */

    static void validateFormula(List<ParticipationCategory> categories) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (categories == null || categories.isEmpty()) {
            throw new FieldValidationException(Map.of("categories", "At least one category is required."));
        }
        Set<String> keys = new HashSet<>();
        int sum = 0;
        boolean assignmentsPresent = false;
        for (int i = 0; i < categories.size(); i++) {
            ParticipationCategory c = categories.get(i);
            if (c.key() == null || c.key().isBlank()) {
                errors.put("categories[" + i + "].key", "Key is required.");
            } else if (!keys.add(c.key())) {
                errors.put("categories[" + i + "].key", "Duplicate key '" + c.key() + "'.");
            }
            if (c.label() == null || c.label().isBlank()) {
                errors.put("categories[" + i + "].label", "Label is required.");
            }
            if (c.weight() < 0 || c.weight() > 100) {
                errors.put("categories[" + i + "].weight", "Weight must be between 0 and 100.");
            }
            sum += c.weight();
            if (ASSIGNMENTS_KEY.equals(c.key())) {
                assignmentsPresent = true;
                if (!c.computed()) {
                    errors.put("categories[" + i + "].computed",
                            "The Assignments category is always computed.");
                }
            }
        }
        if (!assignmentsPresent) {
            errors.put("categories", "The Assignments category is protected and cannot be removed.");
        } else if (sum != 100) {
            errors.put("categories", "Weights must sum to 100 (currently " + sum + ").");
        }
        throwIfInvalid(errors);
    }

    static void validateQualityTags(List<QualityTag> tags) {
        Map<String, String> errors = new LinkedHashMap<>();
        if (tags == null || tags.isEmpty()) {
            throw new FieldValidationException(Map.of("tags", "At least one tag is required."));
        }
        Set<String> keys = new HashSet<>();
        for (int i = 0; i < tags.size(); i++) {
            QualityTag t = tags.get(i);
            if (t.key() == null || t.key().isBlank()) {
                errors.put("tags[" + i + "].key", "Key is required.");
            } else if (!keys.add(t.key())) {
                errors.put("tags[" + i + "].key", "Duplicate key '" + t.key() + "'.");
            }
            if (t.label() == null || t.label().isBlank()) {
                errors.put("tags[" + i + "].label", "Label is required.");
            }
        }
        throwIfInvalid(errors);
    }

    private static void throwIfInvalid(Map<String, String> errors) {
        if (!errors.isEmpty()) {
            throw new FieldValidationException(errors);
        }
    }

    /* -------------------------------------------------------------- plumbing */

    /** JSON envelopes — one stable document shape per section. */
    record CategoriesDoc(List<ParticipationCategory> categories) {
    }

    record BandsDoc(List<ScoringBands.Band> bands) {
    }

    record TagsDoc(List<QualityTag> tags) {
    }

    record NarrativeDoc(String notEnoughDataSentence, String declineCloseInstruction) {
    }

    private interface SectionFactory<V, S> {
        S create(V value, java.time.Instant updatedAt, String updatedByEmail);
    }

    private <D, V, S> S section(String key, Class<D> docType, Function<D, V> unwrap,
                                java.util.function.Supplier<V> defaults, SectionFactory<V, S> factory) {
        Optional<PlatformSetting> row = settings.findById(key);
        V value = row.map(PlatformSetting::getValueText)
                .filter(s -> s != null && !s.isBlank())
                .flatMap(json -> tryParse(key, json, docType))
                .map(unwrap)
                .orElseGet(defaults);
        return factory.create(value,
                row.map(PlatformSetting::getUpdatedAt).orElse(null),
                row.map(PlatformSetting::getUpdatedBy).map(this::emailOf).orElse(null));
    }

    private <D> Optional<D> tryParse(String key, String json, Class<D> docType) {
        try {
            return Optional.of(MAPPER.readValue(json, docType));
        } catch (JsonProcessingException e) {
            log.warn("Stored scoring config under key '{}' is unparseable; using defaults: {}",
                    key, e.getOriginalMessage());
            return Optional.empty();
        }
    }

    private void save(String key, Object doc, UUID actorId) {
        String json;
        try {
            json = MAPPER.writeValueAsString(doc);
        } catch (JsonProcessingException e) {
            throw new BadRequestException("Failed to serialize config: " + e.getMessage());
        }
        PlatformSetting setting = settings.findById(key).orElseGet(() -> {
            PlatformSetting fresh = new PlatformSetting();
            fresh.setKey(key);
            return fresh;
        });
        setting.setValueText(json);
        setting.setValueInt(null);
        setting.setUpdatedAt(java.time.Instant.now());
        setting.setUpdatedBy(actorId);
        settings.save(setting);

        auditLogger.log(actorId, null, "SCORING_CONFIG_UPDATED", "Platform", null,
                Map.of("section", key));
    }

    /** Raw lookup, not an auth import: the ArchUnit ratchet forbids platform→auth. */
    private String emailOf(UUID userId) {
        return jdbc.query("SELECT email FROM users WHERE id = :id",
                        new MapSqlParameterSource("id", userId), (rs, i) -> rs.getString("email"))
                .stream().findFirst().orElse(null);
    }
}
