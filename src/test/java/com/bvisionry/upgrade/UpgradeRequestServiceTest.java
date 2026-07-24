package com.bvisionry.upgrade;

import com.bvisionry.audit.AuditService;
import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.config.FrontendUrls;
import com.bvisionry.notification.EmailService;
import com.bvisionry.organization.entity.Organization;
import com.bvisionry.upgrade.UpgradePromptLoader.UpgradePrompt;
import com.bvisionry.upgrade.dto.UpgradeRequestCreateRequest;
import com.bvisionry.upgrade.entity.UpgradeRequest;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Sub-org members request upgrades on behalf of their ROOT org — the only org
 * whose tier a super admin can change (sub-org rows are permanently FREE).
 */
@ExtendWith(MockitoExtension.class)
class UpgradeRequestServiceTest {

    @Mock UpgradeRequestRepository requestRepo;
    @Mock UserRepository userRepo;
    @Mock UpgradeRequestRecipientService recipientService;
    @Mock UpgradePromptService promptService;
    @Mock EmailService emailService;
    @Mock AuditService auditService;
    @Mock FrontendUrls frontendUrls;

    @InjectMocks UpgradeRequestService service;

    private Organization parent;
    private Organization subOrg;
    private User member;
    private UUID memberId;

    @BeforeEach
    void setUp() {
        parent = new Organization();
        parent.setId(UUID.randomUUID());
        parent.setName("Parent Org");
        parent.setActive(true);

        subOrg = new Organization();
        subOrg.setId(UUID.randomUUID());
        subOrg.setName("General");
        subOrg.setActive(true);
        subOrg.setSubscriptionTier(SubscriptionTier.FREE);
        subOrg.setParentOrganization(parent);

        memberId = UUID.randomUUID();
        member = new User();
        member.setId(memberId);
        member.setName("Member");
        member.setEmail("member@t.invalid");
        member.setRole(UserRole.MEMBER);
        member.setOrganization(subOrg);

        when(userRepo.findByIdWithOrganization(memberId)).thenReturn(Optional.of(member));
    }

    /** The sub-org row is FREE, but the plan that matters is the parent's PREMIUM. */
    @Test
    void create_subOrgMemberUnderPremiumParent_rejectedAsAlreadyPremium() {
        parent.setSubscriptionTier(SubscriptionTier.PREMIUM);

        assertThatThrownBy(() -> service.create(memberId, new UpgradeRequestCreateRequest(null, null)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("already on the PREMIUM plan");
    }

    @Test
    void create_subOrgMemberUnderFreeParent_createsRequestAgainstParentOrg() {
        parent.setSubscriptionTier(SubscriptionTier.FREE);
        when(promptService.get()).thenReturn(new UpgradePrompt(
                "h", List.of(), "n", "p", "b", "ht", "ch", "cb", 24));
        when(requestRepo.findFirstByRequestedBy_IdOrderByCreatedAtDesc(memberId))
                .thenReturn(Optional.empty());
        when(requestRepo.save(any(UpgradeRequest.class))).thenAnswer(inv -> {
            UpgradeRequest r = inv.getArgument(0);
            r.setId(UUID.randomUUID());
            r.setCreatedAt(Instant.now());
            return r;
        });
        when(recipientService.resolveRecipients()).thenReturn(List.of("admin@t.invalid"));
        when(frontendUrls.path(anyString())).thenAnswer(inv -> "https://fe" + inv.getArgument(0));

        service.create(memberId, new UpgradeRequestCreateRequest(null, null));

        // Request row, audit entry, and dashboard link all target the PARENT org.
        ArgumentCaptor<UpgradeRequest> saved = ArgumentCaptor.forClass(UpgradeRequest.class);
        verify(requestRepo).save(saved.capture());
        assertThat(saved.getValue().getOrganization()).isSameAs(parent);
        verify(auditService).log(eq(memberId), eq(parent.getId()), anyString(), anyString(),
                eq(parent.getId()), any());
        verify(emailService).sendUpgradeRequestedAsync(eq("admin@t.invalid"), eq("Parent Org"),
                eq("Member"), eq("member@t.invalid"), anyString(), any(),
                contains(parent.getId().toString()));
    }
}
