package com.bvisionry.audit;

import com.bvisionry.audit.entity.AuditLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;

@ExtendWith(MockitoExtension.class)
class AuditServiceTest {

    @Mock
    private AuditRepository auditRepository;

    @InjectMocks
    private AuditService auditService;

    @Test
    void log_savesAuditEntry() {
        UUID actorId = UUID.randomUUID();
        UUID orgId = UUID.randomUUID();
        UUID entityId = UUID.randomUUID();
        Map<String, Object> details = Map.of("oldTier", "FREE", "newTier", "PREMIUM");

        auditService.log(actorId, orgId, "TIER_CHANGE", "Organization", entityId, details);

        ArgumentCaptor<AuditLog> captor = ArgumentCaptor.forClass(AuditLog.class);
        verify(auditRepository).save(captor.capture());

        AuditLog saved = captor.getValue();
        assertThat(saved.getActorId()).isEqualTo(actorId);
        assertThat(saved.getOrganizationId()).isEqualTo(orgId);
        assertThat(saved.getActionType()).isEqualTo("TIER_CHANGE");
        assertThat(saved.getEntityType()).isEqualTo("Organization");
        assertThat(saved.getEntityId()).isEqualTo(entityId);
        assertThat(saved.getDetailsJson()).containsEntry("oldTier", "FREE");
    }
}
