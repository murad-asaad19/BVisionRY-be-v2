package com.bvisionry.platform;

import com.bvisionry.audit.AuditService;
import com.bvisionry.media.MediaService;
import com.bvisionry.organization.OrgAuditActions;
import com.bvisionry.platform.dto.LeadMagnetPdfResponse;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Read/persist logic for the single lead-magnet PDF — the research paper emailed
 * to visitors who request "the science behind the 11 pillars". The marker
 * ({@code minio://bucket/key} from the media upload, or an external URL) is the
 * single source of truth, stored as the {@code value_text} of one
 * {@link PlatformSetting} row. The display file name and preview URL are derived
 * from the marker, so there is nothing else to keep in sync.
 */
@Service
@RequiredArgsConstructor
public class LeadMagnetSettingsService {

    /** platform_settings key holding the configured PDF marker. */
    static final String SETTING_KEY = "lead_magnet.pdf_marker";

    private static final String MINIO_SCHEME = "minio://";
    private static final String DEFAULT_FILE_NAME = "founder-readiness.pdf";

    /** Leading "<uuid>-" that {@code MediaService.upload} prepends to the object key. */
    private static final Pattern UPLOAD_UUID_PREFIX = Pattern.compile(
            "^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}-");

    private final PlatformSettingRepository settingRepo;
    private final MediaService mediaService;
    private final AuditService auditService;

    @Transactional(readOnly = true)
    public LeadMagnetPdfResponse get() {
        String marker = currentMarker();
        return toResponse(marker);
    }

    /** Raw configured marker, or {@code null} when no PDF is set. Used by the email flow. */
    @Transactional(readOnly = true)
    public String currentMarker() {
        return settingRepo.findById(SETTING_KEY)
                .map(PlatformSetting::getValueText)
                .filter(s -> s != null && !s.isBlank())
                .map(String::trim)
                .orElse(null);
    }

    @Transactional
    public LeadMagnetPdfResponse save(String rawMarker, UUID actorId) {
        String marker = rawMarker == null || rawMarker.isBlank() ? null : rawMarker.trim();

        PlatformSetting setting = settingRepo.findById(SETTING_KEY).orElseGet(() -> {
            PlatformSetting fresh = new PlatformSetting();
            fresh.setKey(SETTING_KEY);
            return fresh;
        });
        if (Objects.equals(setting.getValueText(), marker)) {
            return toResponse(marker);
        }
        setting.setValueText(marker);
        setting.setValueInt(null);
        setting.setUpdatedAt(Instant.now());
        setting.setUpdatedBy(actorId);
        settingRepo.save(setting);

        Map<String, Object> details = new HashMap<>();
        details.put("configured", marker != null);
        details.put("fileName", deriveFileName(marker));
        auditService.log(actorId, null, OrgAuditActions.LEAD_MAGNET_PDF_UPDATED,
                OrgAuditActions.ENTITY_PLATFORM, null, details);

        return toResponse(marker);
    }

    /** Derives a human-friendly file name from the stored marker. */
    public String deriveFileName(String marker) {
        if (marker == null || marker.isBlank()) {
            return null;
        }
        String path = marker.startsWith(MINIO_SCHEME) ? marker.substring(MINIO_SCHEME.length()) : marker;
        // Drop any query string (external URLs) then take the last path segment.
        int q = path.indexOf('?');
        if (q >= 0) path = path.substring(0, q);
        int slash = path.lastIndexOf('/');
        String last = slash >= 0 ? path.substring(slash + 1) : path;
        // Strip the upload UUID prefix MediaService adds (minio markers only).
        last = UPLOAD_UUID_PREFIX.matcher(last).replaceFirst("");
        // The lead magnet is always a PDF. An external URL with a trailing slash
        // or no filename segment leaves a host string / extension-less token here
        // (e.g. "cdn.example.com", "download"), which makes a junk attachment name
        // — fall back to the default research-paper name unless it looks like a PDF.
        return last.toLowerCase().endsWith(".pdf") ? last : DEFAULT_FILE_NAME;
    }

    private LeadMagnetPdfResponse toResponse(String marker) {
        if (marker == null) {
            return new LeadMagnetPdfResponse(null, null, null);
        }
        return new LeadMagnetPdfResponse(marker, deriveFileName(marker), mediaService.resolveUrl(marker));
    }
}
