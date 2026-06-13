package com.bvisionry.platform;

import com.bvisionry.auth.SecurityUtils;
import com.bvisionry.organization.AttentionThresholdsService;
import com.bvisionry.platform.dto.AttentionThresholdsRequest;
import com.bvisionry.platform.dto.AttentionThresholdsResponse;
import com.bvisionry.upgrade.UpgradePromptLoader.UpgradePrompt;
import com.bvisionry.upgrade.UpgradePromptService;
import com.bvisionry.upgrade.UpgradeRequestRecipientService;
import com.bvisionry.upgrade.dto.UpgradeRequestRecipientsRequest;
import com.bvisionry.upgrade.dto.UpgradeRequestRecipientsResponse;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/settings")
@RequiredArgsConstructor
@PreAuthorize("hasAuthority('SUPER_ADMIN')")
public class PlatformSettingsController {

    private final AttentionThresholdsService attentionThresholdsService;
    private final UpgradeRequestRecipientService upgradeRequestRecipientService;
    private final UpgradePromptService upgradePromptService;

    @GetMapping("/attention-thresholds")
    public ResponseEntity<AttentionThresholdsResponse> get() {
        return ResponseEntity.ok(attentionThresholdsService.get());
    }

    @PutMapping("/attention-thresholds")
    public ResponseEntity<AttentionThresholdsResponse> update(
            @Valid @RequestBody AttentionThresholdsRequest req) {
        return ResponseEntity.ok(attentionThresholdsService.setAll(req, SecurityUtils.getCurrentUserId()));
    }

    @GetMapping("/upgrade-request-recipients")
    public ResponseEntity<UpgradeRequestRecipientsResponse> getUpgradeRequestRecipients() {
        var view = upgradeRequestRecipientService.get();
        return ResponseEntity.ok(
                new UpgradeRequestRecipientsResponse(view.recipients(), view.fallbackToSuperAdmins()));
    }

    @PutMapping("/upgrade-request-recipients")
    public ResponseEntity<UpgradeRequestRecipientsResponse> setUpgradeRequestRecipients(
            @Valid @RequestBody UpgradeRequestRecipientsRequest req) {
        var view = upgradeRequestRecipientService.setRecipients(
                req.recipients(), SecurityUtils.getCurrentUserId());
        return ResponseEntity.ok(
                new UpgradeRequestRecipientsResponse(view.recipients(), view.fallbackToSuperAdmins()));
    }

    @GetMapping("/upgrade-prompt")
    public ResponseEntity<UpgradePromptService.View> getUpgradePrompt() {
        return ResponseEntity.ok(upgradePromptService.view());
    }

    @PutMapping("/upgrade-prompt")
    public ResponseEntity<UpgradePrompt> setUpgradePrompt(@RequestBody UpgradePrompt prompt) {
        return ResponseEntity.ok(upgradePromptService.set(prompt, SecurityUtils.getCurrentUserId()));
    }

    @DeleteMapping("/upgrade-prompt")
    public ResponseEntity<UpgradePrompt> resetUpgradePrompt() {
        return ResponseEntity.ok(upgradePromptService.reset(SecurityUtils.getCurrentUserId()));
    }
}
