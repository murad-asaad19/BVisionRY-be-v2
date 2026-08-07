package com.bvisionry.media;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.bvisionry.common.exception.IllegalOperationException;

import io.minio.ListObjectsArgs;
import io.minio.MinioClient;
import io.minio.RemoveObjectArgs;
import io.minio.Result;
import io.minio.messages.Item;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Per-org storage quota: usage computed live off MinIO's own object sizes (no
 * counter table — see the class javadoc), override precedence, cross-org
 * isolation, and the two enforcement points ({@code requireCapacity} at
 * initiation, {@code reconcileAfterUpload} at consume).
 */
class OrgStorageQuotaServiceTest {

    private static final UUID ORG = UUID.fromString("11111111-2222-3333-4444-555555555555");
    private static final UUID OTHER_ORG = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final String BUCKET = "bvisionry-media";
    private static final long ONE_MIB = 1024L * 1024;

    private MinioClient internalClient;
    private OrgStorageQuotaRepository quotaRepository;
    private MediaProperties props;
    private OrgStorageQuotaService service;

    @BeforeEach
    void setUp() {
        internalClient = mock(MinioClient.class);
        quotaRepository = mock(OrgStorageQuotaRepository.class);
        props = new MediaProperties();
        props.setBucket(BUCKET);
        props.setOrgDefaultQuotaBytes(10 * ONE_MIB);
        service = new OrgStorageQuotaService(internalClient, props, quotaRepository);
    }

    // ------------------------------------------------------------- override precedence

    @Test
    void aNullOverrideFallsBackToThePlatformDefault() {
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null);

        assertThat(service.effectiveQuotaBytes(ORG)).isEqualTo(10 * ONE_MIB);
    }

    @Test
    void anOrgOverrideWinsOverThePlatformDefault() {
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(500L * ONE_MIB);

        assertThat(service.effectiveQuotaBytes(ORG)).isEqualTo(500L * ONE_MIB);
    }

    // ------------------------------------------------------------- boundary: under/at/over

    @Test
    void underQuotaAllowsTheUpload() throws Exception {
        stubUsage(ORG, 4 * ONE_MIB);
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null); // 10 MiB default

        service.requireCapacity(ORG, 4 * ONE_MIB); // 4 + 4 = 8 <= 10, must not throw
    }

    @Test
    void exactlyAtQuotaAllowsTheUpload() throws Exception {
        stubUsage(ORG, 4 * ONE_MIB);
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null); // 10 MiB default

        service.requireCapacity(ORG, 6 * ONE_MIB); // 4 + 6 == 10, boundary is inclusive
    }

    @Test
    void oneByteOverQuotaRefusesTheUpload() throws Exception {
        stubUsage(ORG, 4 * ONE_MIB);
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null); // 10 MiB default

        assertThatThrownBy(() -> service.requireCapacity(ORG, 6 * ONE_MIB + 1))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("quota");
    }

    @Test
    void refusalMessageReportsUsageQuotaAndTheIncomingSize() throws Exception {
        stubUsage(ORG, 9 * ONE_MIB);
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null);

        assertThatThrownBy(() -> service.requireCapacity(ORG, 2 * ONE_MIB))
                .hasMessageContaining("9.0 MiB")
                .hasMessageContaining("10.0 MiB")
                .hasMessageContaining("2.0 MiB");
    }

    // ------------------------------------------------------------- cross-org isolation

    @Test
    void usageIsScopedToTheCallingOrgsOwnPrefix() throws Exception {
        stubUsage(ORG, 1 * ONE_MIB);
        stubUsage(OTHER_ORG, 9 * ONE_MIB);
        when(quotaRepository.quotaOverrideBytes(any())).thenReturn(null); // both default 10 MiB

        // OTHER_ORG is nearly at its own quota; ORG's usage must not see it.
        service.requireCapacity(ORG, 8 * ONE_MIB); // 1 + 8 = 9 <= 10, must not throw

        ArgumentCaptor<ListObjectsArgs> args = ArgumentCaptor.forClass(ListObjectsArgs.class);
        verify(internalClient).listObjects(args.capture());
        assertThat(args.getValue().prefix()).isEqualTo("org/" + ORG + "/");
    }

    @Test
    void anOtherOrgsUsageNeverCountsAgainstThisOrgsQuota() throws Exception {
        stubUsage(ORG, 1 * ONE_MIB);
        stubUsage(OTHER_ORG, 50 * ONE_MIB); // way over ITS OWN 10 MiB default
        when(quotaRepository.quotaOverrideBytes(any())).thenReturn(null);

        // Would throw if OTHER_ORG's usage leaked into this computation.
        service.requireCapacity(ORG, 8 * ONE_MIB);
    }

    // ------------------------------------------------------------- reconcile path

    @Test
    void reconcileIsANoOpWhenTheOrgIsUnderQuotaAfterUpload() throws Exception {
        String marker = "minio://" + BUCKET + "/org/" + ORG + "/branding/x-logo.png";
        stubUsage(ORG, 4 * ONE_MIB); // already includes the just-uploaded object
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null);

        service.reconcileAfterUpload(ORG, marker);

        verify(internalClient, never()).removeObject(any(RemoveObjectArgs.class));
    }

    @Test
    void reconcileDeletesTheObjectAndRefusesWhenOverQuotaAfterUpload() throws Exception {
        String marker = "minio://" + BUCKET + "/org/" + ORG + "/branding/x-logo.png";
        stubUsage(ORG, 11 * ONE_MIB); // over the 10 MiB default, real MinIO size
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null);

        assertThatThrownBy(() -> service.reconcileAfterUpload(ORG, marker))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("quota");

        ArgumentCaptor<RemoveObjectArgs> removed = ArgumentCaptor.forClass(RemoveObjectArgs.class);
        verify(internalClient).removeObject(removed.capture());
        assertThat(removed.getValue().object()).isEqualTo("org/" + ORG + "/branding/x-logo.png");
    }

    @Test
    void aDeleteFailureDuringReconcileStillRefusesRatherThanMasking500() throws Exception {
        String marker = "minio://" + BUCKET + "/org/" + ORG + "/branding/x-logo.png";
        stubUsage(ORG, 11 * ONE_MIB);
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null);
        doThrow(new RuntimeException("minio unreachable"))
                .when(internalClient).removeObject(any(RemoveObjectArgs.class));

        assertThatThrownBy(() -> service.reconcileAfterUpload(ORG, marker))
                .isInstanceOf(IllegalOperationException.class);
    }

    // ------------------------------------------------------------------- helpers

    /** Makes listing {@code org/<orgId>/} in the configured bucket report {@code totalBytes}. */
    @SuppressWarnings("unchecked")
    private void stubUsage(UUID orgId, long totalBytes) throws Exception {
        Item item = mock(Item.class);
        doReturn(totalBytes).when(item).size();
        Result<Item> result = new Result<>(item);

        doReturn(List.of(result)).when(internalClient).listObjects(
                argThatPrefixIs("org/" + orgId + "/"));
    }

    private static ListObjectsArgs argThatPrefixIs(String prefix) {
        return org.mockito.ArgumentMatchers.argThat(
                a -> a != null && prefix.equals(a.prefix()));
    }
}
