package com.bvisionry.organization;

import com.bvisionry.audit.AuditRepository;
import com.bvisionry.audit.entity.AuditLog;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.organization.dto.ActivityFeedResponse;
import com.bvisionry.organization.dto.ActivityItem;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ActivityService {

    private final AuditRepository auditRepo;
    private final UserRepository userRepo;

    public ActivityFeedResponse getActivity(UUID orgId, int limit) {
        int safeLimit = Math.max(1, Math.min(limit, 100));
        List<AuditLog> rows = auditRepo.findOrgScopedActivity(orgId, PageRequest.of(0, safeLimit));

        // Batch-load actor names in one round-trip — replaces the previous
        // per-row userRepo.findById N+1.
        List<UUID> actorIds = rows.stream()
                .map(AuditLog::getActorId)
                .filter(Objects::nonNull)
                .distinct()
                .toList();
        Map<UUID, String> namesByActor = actorIds.isEmpty()
                ? Map.of()
                : userRepo.findAllById(actorIds).stream()
                        .collect(Collectors.toMap(User::getId, User::getName));

        List<ActivityItem> items = rows.stream()
                .map(row -> toItem(row, namesByActor))
                .toList();
        return new ActivityFeedResponse(items);
    }

    private ActivityItem toItem(AuditLog row, Map<UUID, String> namesByActor) {
        String actorName = row.getActorId() == null
                ? "System"
                : namesByActor.getOrDefault(row.getActorId(), "System");
        String summary = humanize(row.getActionType(), row.getDetailsJson());
        return new ActivityItem(
                row.getId(), row.getOccurredAt(), row.getActorId(), actorName,
                row.getActionType(), summary, row.getDetailsJson());
    }

    private String humanize(String action, Map<String, Object> details) {
        return switch (action) {
            case OrgAuditActions.ORGANIZATION_CREATED     -> "Organization created";
            case OrgAuditActions.ORGANIZATION_UPDATED     -> "Organization details updated";
            case OrgAuditActions.ORGANIZATION_SUSPENDED   -> "Organization suspended";
            case OrgAuditActions.ORGANIZATION_REACTIVATED -> "Organization reactivated";
            case OrgAuditActions.ORGANIZATION_DELETED     -> "Organization deleted";
            case OrgAuditActions.TIER_CHANGE              -> "Tier changed to "
                    + (details == null ? "?" : details.getOrDefault("newTier", "?"));
            case OrgAuditActions.TRIAL_STARTED            -> "Trial started ("
                    + (details == null ? "?" : details.getOrDefault("durationDays", "?")) + " days)";
            case OrgAuditActions.TRIAL_EXTENDED            -> "Trial extended";
            case OrgAuditActions.TRIAL_ENDED_EARLY         -> "Trial ended early";
            case OrgAuditActions.TRIAL_EXPIRED             -> "Trial expired";
            case OrgAuditActions.TRIAL_ENDING_SOON_NOTIFIED -> "Trial-ending notification sent";
            case OrgAuditActions.ATTENTION_THRESHOLDS_UPDATED -> "Attention thresholds updated";
            case OrgAuditActions.MEMBER_INVITED            -> "Member invited";
            case OrgAuditActions.MEMBER_ROLE_CHANGED       -> "Member role changed";
            case OrgAuditActions.MEMBER_STATUS_CHANGED     -> "Member status changed";
            case OrgAuditActions.MEMBER_PROFILE_UPDATED    -> "Member profile updated";
            case OrgAuditActions.MEMBER_MOVED              -> "Member moved to another organization";
            case OrgAuditActions.MEMBER_REMOVED            -> "Member removed";
            case OrgAuditActions.JOIN_LINK_USED            -> "Joined via join link";
            case OrgAuditActions.CLEAR_RESPONSES           -> "Assessment responses cleared";
            case OrgAuditActions.USER_ROLE_CHANGED         -> "Platform role changed";
            case OrgAuditActions.ASSESSMENT_ASSIGNED       -> "Assigned assessment "
                    + quote(detail(details, "pipelineName"))
                    + " to " + detail(details, "memberName");
            case OrgAuditActions.ASSESSMENT_SUBMITTED      -> "Submitted assessment "
                    + quote(detail(details, "pipelineName"));
            case OrgAuditActions.ASSESSMENT_EVALUATED      -> "Assessment "
                    + quote(detail(details, "pipelineName")) + " evaluated";
            case OrgAuditActions.SURVEY_RESPONSE_SUBMITTED -> "Submitted survey response "
                    + quote(detail(details, "surveyName"));
            case OrgAuditActions.UPGRADE_REQUESTED         -> "Requested Premium upgrade ("
                    + detail(details, "featureContext") + ")";
            case OrgAuditActions.AUTO_ASSIGN_RULE_CREATED  -> "Auto-assign rule created for "
                    + quote(detail(details, "pipelineName"));
            case OrgAuditActions.AUTO_ASSIGN_RULE_UPDATED  -> "Auto-assign rule updated for "
                    + quote(detail(details, "pipelineName"));
            case OrgAuditActions.AUTO_ASSIGN_RULE_DELETED  -> "Auto-assign rule removed for "
                    + quote(detail(details, "pipelineName"));
            case OrgAuditActions.ASSESSMENT_PILLARS_UNLOCKED -> "Unlocked pillars for re-edit on "
                    + quote(detail(details, "pipelineName"));
            case OrgAuditActions.ASSESSMENT_PILLARS_RELOCKED -> "Relocked pillars on "
                    + quote(detail(details, "pipelineName"));
            case OrgAuditActions.ASSESSMENT_REEVALUATED      -> "Re-evaluated assessment "
                    + quote(detail(details, "pipelineName"));
            case OrgAuditActions.ASSESSMENT_CHECK_IN_STARTED -> "Started a new check-in of "
                    + quote(detail(details, "pipelineName"));
            case OrgAuditActions.UPGRADE_PROMPT_UPDATED      -> "Upgrade prompt copy updated";
            case OrgAuditActions.UPGRADE_PROMPT_RESET        -> "Upgrade prompt reset to defaults";
            case OrgAuditActions.UPGRADE_REQUEST_RECIPIENTS_UPDATED -> "Upgrade notification recipients updated";
            default -> action;
        };
    }

    private static String detail(Map<String, Object> details, String key) {
        if (details == null) return "?";
        Object value = details.get(key);
        return value == null ? "?" : value.toString();
    }

    private static String quote(String value) {
        return "‘" + value + "’";
    }
}
