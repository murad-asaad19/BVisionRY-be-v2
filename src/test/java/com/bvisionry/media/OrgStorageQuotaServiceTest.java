package com.bvisionry.media;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

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

    // ------------------------------------------------------------- scan short-circuit

    /**
     * The usage scan is a full RECURSIVE listing of the org's whole prefix and it runs
     * on every presign, every upload and every branding save. Every caller only asks
     * "is the org over?", and object sizes are non-negative — so once the running
     * total has passed the ceiling the answer cannot change and the rest of the
     * listing is pure cost. Without the short-circuit this walks all 500 objects.
     */
    @Test
    void theUsageScanStopsAsSoonAsTheAnswerIsSettled() throws Exception {
        AtomicInteger visited = new AtomicInteger();
        stubUsage(ORG, 500, ONE_MIB, visited);
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null); // 10 MiB default

        assertThatThrownBy(() -> service.requireCapacity(ORG, 1L))
                .isInstanceOf(IllegalOperationException.class);

        // Limit is 10 MiB - 1, so the 11th object of 1 MiB settles it.
        assertThat(visited).hasValueLessThanOrEqualTo(11);
    }

    /** …and it still reads everything when the org really is under the ceiling. */
    @Test
    void theUsageScanReadsEveryObjectWhenTheOrgIsUnderQuota() throws Exception {
        AtomicInteger visited = new AtomicInteger();
        stubUsage(ORG, 4, ONE_MIB, visited);
        when(quotaRepository.quotaOverrideBytes(ORG)).thenReturn(null);

        service.requireCapacity(ORG, 4 * ONE_MIB); // 4 + 4 = 8 <= 10

        assertThat(visited).hasValue(4);
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

    /**
     * Makes listing {@code org/<orgId>/} report {@code count} objects of
     * {@code eachBytes}, counting how many the service actually pulls. The iterable is
     * lazy on purpose — a pre-built list would be indistinguishable from a full scan.
     */
    @SuppressWarnings("unchecked")
    private void stubUsage(UUID orgId, int count, long eachBytes, AtomicInteger visited)
            throws Exception {
        Iterable<Result<Item>> lazy = () -> new java.util.Iterator<>() {
            private int emitted = 0;

            @Override public boolean hasNext() {
                return emitted < count;
            }

            @Override public Result<Item> next() {
                emitted++;
                visited.incrementAndGet();
                Item item = mock(Item.class);
                doReturn(eachBytes).when(item).size();
                return new Result<>(item);
            }
        };
        doReturn(lazy).when(internalClient).listObjects(argThatPrefixIs("org/" + orgId + "/"));
    }

    private static ListObjectsArgs argThatPrefixIs(String prefix) {
        return org.mockito.ArgumentMatchers.argThat(
                a -> a != null && prefix.equals(a.prefix()));
    }
}
