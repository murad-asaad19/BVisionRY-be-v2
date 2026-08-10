package com.bvisionry.exercise;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.scoringconfig.QualityTags;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * The exercise slice's read of the platform "Scoring &amp; Labels" quality-tag
 * set (spec §7). The document is owned and validated by {@code platform}; here
 * it is read-only, so this reads the {@code platform_settings} row by raw SQL
 * rather than importing that slice's service — the same shape
 * {@code EngagementReadRepository} uses for the participation formula.
 *
 * <p>Validation is against the CURRENT config: a tag that has since been
 * deleted cannot be applied again, while submissions already carrying it keep
 * their stored key AND the label snapshot taken when it was applied.
 */
@Component
@RequiredArgsConstructor
@Slf4j
public class QualityTagCatalog {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final NamedParameterJdbcTemplate jdbc;

    /** JSON envelope — mirrors the platform side's {@code TagsDoc}. */
    private record TagsDoc(List<QualityTags.Tag> tags) {
    }

    /** The live tag set, or the shipped defaults when nothing has been configured. */
    public List<QualityTags.Tag> tags() {
        String json = jdbc.query("SELECT value_text FROM platform_settings WHERE key = :key",
                        new MapSqlParameterSource("key", QualityTags.KEY),
                        (rs, i) -> rs.getString("value_text"))
                .stream().findFirst().orElse(null);
        if (json == null || json.isBlank()) {
            return QualityTags.defaults();
        }
        try {
            List<QualityTags.Tag> tags = MAPPER.readValue(json, TagsDoc.class).tags();
            return tags == null || tags.isEmpty() ? QualityTags.defaults() : tags;
        } catch (JsonProcessingException e) {
            // Lenient like the platform reader: a hand-mangled row must not
            // take the review screen down.
            log.warn("Stored quality-tag config is unparseable; using defaults: {}",
                    e.getOriginalMessage());
            return QualityTags.defaults();
        }
    }

    /**
     * Resolves {@code key} against the live set and returns its label snapshot.
     *
     * @throws BadRequestException when the key is not (or no longer) configured
     */
    public String requireLabel(String key) {
        return tags().stream()
                .filter(t -> t.key().equals(key))
                .findFirst()
                .map(QualityTags.Tag::label)
                .orElseThrow(() -> new BadRequestException(
                        "Unknown quality tag '" + key + "'."));
    }

    /**
     * Display name of the reviewer who tagged, for the §7b stamp. Raw lookup,
     * not an auth import: keeps this bean free of a cross-feature edge, and a
     * name erased by GDPR simply reads as absent (the ledger precedent).
     */
    public String reviewerName(UUID userId) {
        if (userId == null) {
            return null;
        }
        return jdbc.query("SELECT name FROM users WHERE id = :id",
                        new MapSqlParameterSource("id", userId), (rs, i) -> rs.getString("name"))
                .stream().findFirst().orElse(null);
    }
}
