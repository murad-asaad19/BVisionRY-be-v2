package com.bvisionry.engagement.web;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.bvisionry.common.participation.ParticipationBandsPort;
import com.bvisionry.engagement.dto.EngagementRecordResponse.CohortParticipationResponse;
import com.bvisionry.engagement.dto.EngagementRecordResponse.MemberParticipation;

import lombok.RequiredArgsConstructor;

/**
 * The engagement slice's implementation of {@link ParticipationBandsPort}:
 * folds the roster read the Engagement tab renders
 * ({@link EngagementService#cohortParticipation}) into per-band member counts,
 * so an exported band breakdown can never disagree with the on-screen matrix.
 */
@Component
@RequiredArgsConstructor
public class ParticipationBandsAdapter implements ParticipationBandsPort {

    private final EngagementService engagement;

    @Override
    @Transactional(readOnly = true)
    public CohortBands cohortBands(UUID orgId, UUID cohortId) {
        CohortParticipationResponse data = engagement.cohortParticipation(cohortId, orgId);
        int unscored = 0;
        // Insertion-ordered by first sighting, then sorted by band key below —
        // labels are runtime-renameable, the keys (band_1..n) are the identity.
        Map<String, BandCount> counts = new LinkedHashMap<>();
        for (MemberParticipation m : data.members()) {
            if (m.participation().score() == null) {
                unscored++;
                continue;
            }
            counts.merge(m.participation().bandKey(),
                    new BandCount(m.participation().bandKey(), m.participation().bandLabel(), 1),
                    (a, b) -> new BandCount(a.bandKey(), a.bandLabel(), a.members() + 1));
        }
        List<BandCount> bands = new ArrayList<>(counts.values());
        bands.sort(Comparator.comparing(BandCount::bandKey));
        return new CohortBands(data.average(), bands, unscored);
    }
}
