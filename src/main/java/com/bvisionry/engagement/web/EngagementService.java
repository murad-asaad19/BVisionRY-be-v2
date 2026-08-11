package com.bvisionry.engagement.web;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.scoringconfig.ParticipationFormula;
import com.bvisionry.common.scoringconfig.ScoringBands;
import com.bvisionry.engagement.domain.SessionType;
import com.bvisionry.engagement.dto.EngagementRecordResponse;
import com.bvisionry.engagement.dto.EngagementRecordResponse.CohortEngagement;
import com.bvisionry.engagement.dto.EngagementRecordResponse.CohortParticipationResponse;
import com.bvisionry.engagement.dto.EngagementRecordResponse.MemberParticipation;
import com.bvisionry.engagement.dto.EngagementRecordResponse.ParticipationDto;
import com.bvisionry.engagement.dto.EngagementRecordResponse.SessionHistoryItem;
import com.bvisionry.engagement.repository.EngagementReadRepository;
import com.bvisionry.engagement.repository.EngagementReadRepository.Counts;
import com.bvisionry.engagement.web.ParticipationScoring.CategoryInput;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

/**
 * The Engagement Record read (spec §4): per founder × cohort, the
 * participation score computed ON READ against the live Scoring &amp; Labels
 * config, plus the §7b-stamped session history. CALLERS AUTHORIZE FIRST (org
 * guard stack or {@code CoachAccess}); this service re-anchors on the
 * org-scoped member row — a foreign or non-member id is a 404 regardless.
 *
 * <p>Config is read via raw SQL like the comparison slice (the ArchUnit
 * ratchet forbids an engagement→platform import); the JSON envelopes mirror
 * the platform slice's document shapes, defaults from
 * {@link ParticipationFormula} so the two can never drift.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
@Slf4j
public class EngagementService {

    /** Lenient on unknown fields: a future config-doc field must not revert scoring to defaults. */
    private static final ObjectMapper MAPPER = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);

    private final EngagementReadRepository reads;

    public EngagementRecordResponse record(UUID orgId, UUID memberId) {
        if (!reads.isOrgMember(orgId, memberId)) {
            throw new ResourceNotFoundException("Member", memberId.toString());
        }
        List<ParticipationFormula.Category> categories = currentCategories();
        List<ScoringBands.Band> bands = currentBands();
        return new EngagementRecordResponse(reads.memberCohorts(orgId, memberId).stream()
                .map(cohort -> new CohortEngagement(cohort.id(), cohort.name(),
                        participation(cohort.id(), memberId, categories, bands),
                        history(cohort.id(), memberId)))
                .toList());
    }

    /**
     * Every founder in one cohort with their participation score (spec §4:
     * Pulse is a participation surface). Config is read ONCE for the whole
     * roster, so a cohort scores against a single consistent formula.
     *
     * <p>ponytail: two small reads per member (assignment counts + session
     * counts) — one round trip per row, not one HTTP call per row. At a few
     * hundred founders per cohort that is fine; if a cohort ever gets big
     * enough to feel it, the upgrade is to group both reads BY member and
     * join in Java, not to cache the score.
     */
    public CohortParticipationResponse cohortParticipation(UUID cohortId) {
        return cohortParticipation(cohortId, null);
    }

    /**
     * {@link #cohortParticipation(UUID)} cut to one org's own members — the
     * org console's Pulse (spec §13.7). A cohort the org is not assigned to is
     * a 404, not an empty list.
     */
    public CohortParticipationResponse cohortParticipation(UUID cohortId, UUID orgId) {
        if (reads.cohort(cohortId).isEmpty()
                || (orgId != null && !reads.assignedToOrg(cohortId, orgId))) {
            throw new ResourceNotFoundException("Cohort", cohortId.toString());
        }
        List<ParticipationFormula.Category> categories = currentCategories();
        List<ScoringBands.Band> bands = currentBands();

        List<MemberParticipation> members = reads.roster(cohortId, orgId).stream()
                .map(row -> new MemberParticipation(row.id(), row.name(),
                        participation(cohortId, row.id(), categories, bands)))
                .toList();

        List<BigDecimal> scored = members.stream()
                .map(m -> m.participation().score())
                .filter(Objects::nonNull)
                .toList();
        BigDecimal average = scored.isEmpty() ? null
                : scored.stream().reduce(BigDecimal.ZERO, BigDecimal::add)
                        .divide(BigDecimal.valueOf(scored.size()), 1, RoundingMode.HALF_UP);

        return new CohortParticipationResponse(members, average);
    }

    /* ------------------------------------------------------------- compute */

    private ParticipationDto participation(UUID cohortId, UUID memberId,
                                           List<ParticipationFormula.Category> categories,
                                           List<ScoringBands.Band> bands) {
        Map<String, Counts> sessionCounts = new HashMap<>();
        reads.sessionCounts(cohortId, memberId).forEach(row ->
                sessionCounts.put(categoryKeyOf(row.type()),
                        new Counts(row.held(), row.attended())));

        List<CategoryInput> inputs = categories.stream().map(category -> {
            if (ParticipationFormula.ASSIGNMENTS_KEY.equals(category.key())) {
                // ponytail: "assigned" = today's LIVE program tasks + exercise
                // assignments; the full typed task spine replaces this in a
                // later phase.
                Counts counts = reads.assignmentCounts(cohortId, memberId);
                return new CategoryInput(category, counts.done(), counts.total());
            }
            Counts counts = sessionCounts.getOrDefault(category.key(), new Counts(0, 0));
            return new CategoryInput(category, counts.done(), counts.total());
        }).toList();

        return ParticipationScoring.score(inputs, bands, Instant.now());
    }

    /** The session type's category key; null for an unknown stored value. */
    private static String categoryKeyOf(String storedType) {
        try {
            return SessionType.valueOf(storedType).categoryKey();
        } catch (IllegalArgumentException e) {
            return storedType; // never matches a category → contributes nothing
        }
    }

    private List<SessionHistoryItem> history(UUID cohortId, UUID memberId) {
        return reads.sessionHistory(cohortId, memberId).stream()
                .map(row -> new SessionHistoryItem(row.sessionId(), row.type(), row.title(),
                        row.sessionDate(), row.markedAt() != null, row.markedAt()))
                .toList();
    }

    /* -------------------------------------------------------------- config */

    /** Mirrors the platform slice's {@code CategoriesDoc} envelope. */
    record CategoriesDoc(List<ParticipationFormula.Category> categories) {
    }

    /** Mirrors the platform slice's {@code BandsDoc} envelope. */
    record BandsDoc(List<ScoringBands.Band> bands) {
    }

    List<ParticipationFormula.Category> currentCategories() {
        return reads.settingJson(ParticipationFormula.FORMULA_KEY)
                .flatMap(json -> parse(json, CategoriesDoc.class)
                        .map(CategoriesDoc::categories))
                .orElseGet(ParticipationFormula::defaultCategories);
    }

    List<ScoringBands.Band> currentBands() {
        return reads.settingJson(ParticipationFormula.PARTICIPATION_BANDS_KEY)
                .flatMap(json -> parse(json, BandsDoc.class).map(BandsDoc::bands))
                .orElseGet(ParticipationFormula::defaultParticipationBands);
    }

    private <D> Optional<D> parse(String json, Class<D> docType) {
        try {
            return Optional.of(MAPPER.readValue(json, docType));
        } catch (JsonProcessingException e) {
            log.warn("Stored participation config is unparseable; using defaults: {}",
                    e.getOriginalMessage());
            return Optional.empty();
        }
    }
}
