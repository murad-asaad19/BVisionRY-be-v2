package com.bvisionry.platform;

import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.platform.dto.ScoringConfigRequests.UpdateBandsRequest;
import com.bvisionry.platform.dto.ScoringConfigRequests.UpdateNarrativeWordingRequest;
import com.bvisionry.platform.dto.ScoringConfigRequests.UpdateParticipationFormulaRequest;
import com.bvisionry.platform.dto.ScoringConfigRequests.UpdateQualityTagsRequest;
import com.bvisionry.platform.dto.ScoringConfigResponse;
import com.bvisionry.platform.dto.ScoringConfigResponse.BandsSection;
import com.bvisionry.platform.dto.ScoringConfigResponse.NarrativeWordingSection;
import com.bvisionry.platform.dto.ScoringConfigResponse.ParticipationFormulaSection;
import com.bvisionry.platform.dto.ScoringConfigResponse.QualityTagsSection;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.MediaType;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * "Scoring &amp; Labels" — SUPER_ADMIN-only platform config (spec §7). One GET
 * for the whole page, one PUT per section; structural validation failures come
 * back as a 400 with {@code fieldErrors}.
 */
@RestController
@RequestMapping(path = "/api/admin/scoring-config", produces = MediaType.APPLICATION_JSON_VALUE)
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
@RequiredArgsConstructor
@Tag(name = "Scoring & Labels", description = "Platform scoring formulas, bands, tags and wording.")
public class ScoringConfigController {

    private final ScoringConfigService service;
    private final CurrentUserAccessor currentUser;

    @GetMapping
    public ScoringConfigResponse get() {
        return service.get();
    }

    @PutMapping("/participation-formula")
    public ParticipationFormulaSection putFormula(
            @Valid @RequestBody UpdateParticipationFormulaRequest req) {
        return service.putFormula(req.categories(), currentUser.require().userId());
    }

    @PutMapping("/participation-bands")
    public BandsSection putParticipationBands(@Valid @RequestBody UpdateBandsRequest req) {
        return service.putParticipationBands(req.bands(), currentUser.require().userId());
    }

    @PutMapping("/shift-bands")
    public BandsSection putShiftBands(@Valid @RequestBody UpdateBandsRequest req) {
        return service.putShiftBands(req.bands(), currentUser.require().userId());
    }

    @PutMapping("/quality-tags")
    public QualityTagsSection putQualityTags(@Valid @RequestBody UpdateQualityTagsRequest req) {
        return service.putQualityTags(req.tags(), currentUser.require().userId());
    }

    @PutMapping("/narrative-wording")
    public NarrativeWordingSection putNarrativeWording(
            @Valid @RequestBody UpdateNarrativeWordingRequest req) {
        return service.putNarrativeWording(req.notEnoughDataSentence(),
                req.declineCloseInstruction(), currentUser.require().userId());
    }
}
