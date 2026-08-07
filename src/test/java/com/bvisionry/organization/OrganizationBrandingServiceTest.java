package com.bvisionry.organization;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;
import com.bvisionry.common.media.MediaQuotaPort;
import com.bvisionry.common.media.MediaUrlPort;
import com.bvisionry.common.security.CurrentUser;
import com.bvisionry.common.security.CurrentUserAccessor;
import com.bvisionry.organization.dto.BrandingResponse;
import com.bvisionry.organization.dto.UpdateBrandingRequest;
import com.bvisionry.organization.entity.Organization;
import org.junit.jupiter.api.BeforeEach;
import org.mockito.ArgumentCaptor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * The white-label branding write path, and above all THE IDOR GUARD.
 *
 * <p>A stored {@code minio://} marker is turned into a presigned GET for
 * whatever object key it names. An ORG_ADMIN can now upload media and can
 * therefore also submit an arbitrary marker string, so a branding write that
 * merely persisted what it was given would mint a readable URL for any object
 * in the shared bucket — every other tenant's PDFs and videos. The guard is
 * "the marker's key prefix must name the org being written", and this class
 * exists to make deleting it a failing build.
 */
class OrganizationBrandingServiceTest {

    private static final UUID ORG = UUID.fromString("aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee");
    private static final UUID OTHER_ORG = UUID.fromString("00000000-1111-2222-3333-444444444444");
    private static final UUID ACTOR = UUID.fromString("99999999-8888-7777-6666-555555555555");
    private static final String BUCKET = "bvisionry-media";

    private OrganizationRepository repository;
    private MediaUrlPort mediaUrls;
    private MediaQuotaPort mediaQuota;
    private AuditLogger auditLogger;
    private CurrentUserAccessor currentUser;
    private OrganizationBrandingService service;
    private Organization org;

    /** Plain `captor()`, not @Captor — MockitoExtension's strict stubbing would
     *  fail every test that refuses a write before reaching `save`. */
    private final ArgumentCaptor<Map<String, Object>> detailsCaptor = ArgumentCaptor.captor();

    @BeforeEach
    void setUp() {
        repository = mock(OrganizationRepository.class);
        mediaUrls = mock(MediaUrlPort.class);
        // Unstubbed void reconcileAfterUpload(...) is a no-op — tests that don't
        // care about quota (the great majority here) are unaffected by its
        // presence; the quota-specific tests below stub/verify it explicitly.
        mediaQuota = mock(MediaQuotaPort.class);
        auditLogger = mock(AuditLogger.class);
        currentUser = mock(CurrentUserAccessor.class);
        lenient().when(currentUser.require())
                .thenReturn(new CurrentUser(ACTOR, ORG, "Test Admin", "ORG_ADMIN"));
        service = new OrganizationBrandingService(repository, mediaUrls, mediaQuota, auditLogger, currentUser);

        org = new Organization();
        org.setName("Acme Accelerator");
        when(repository.findById(ORG)).thenReturn(Optional.of(org));
        when(repository.save(any(Organization.class))).thenAnswer(inv -> inv.getArgument(0));
    }

    private static String ownMarker() {
        return "minio://" + BUCKET + "/org/" + ORG + "/branding/" + UUID.randomUUID() + "-logo.png";
    }

    // ------------------------------------------------------------ IDOR guard

    @Test
    void refusesAMarkerBelongingToAnotherOrg() {
        String foreign = "minio://" + BUCKET + "/org/" + OTHER_ORG + "/branding/"
                + UUID.randomUUID() + "-logo.png";

        assertThatThrownBy(() -> service.update(ORG, new UpdateBrandingRequest("#112233", foreign)))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining(ORG.toString());
        verify(repository, never()).save(any());
    }

    /**
     * Every shape that would hand the caller a presigned GET for something that
     * is not their own branding image. Each one must be refused BEFORE anything
     * is written — a marker that reaches the column is a marker that will be
     * presigned.
     */
    @ParameterizedTest
    @ValueSource(strings = {
            // another tenant's lesson media — the whole point of the guard
            "minio://bvisionry-media/pdf/2f1c-secret-financials.pdf",
            "minio://bvisionry-media/video/9a7d-private-session.mp4",
            // right prefix, wrong org (upper case, to prove the compare is on the id)
            "minio://bvisionry-media/org/00000000-1111-2222-3333-444444444444/branding/x-logo.png",
            // traversal back out of the org folder
            "minio://bvisionry-media/org/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/branding/../../pdf/secret.pdf",
            // a nested key that merely STARTS inside the org folder
            "minio://bvisionry-media/org/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/branding/sub/../../../x.pdf",
            // org id smuggled into the filename rather than the path
            "minio://bvisionry-media/pdf/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee-secret.pdf",
            // THE `LIKE` HOLE: a nested key that CONTAINS this org's prefix but
            // names the victim's object. `LIKE 'minio://%/org/'||id||'/branding/%'`
            // accepts this (SQL's % matches /); the anchored regex does not.
            "minio://bvisionry-media/org/00000000-1111-2222-3333-444444444444/branding/x/org/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/branding/y.png",
            // Right org, UPPER-CASE. V154 compares against a lower-case id::text
            // with a case-sensitive operator, so accepting this here would turn a
            // clean 400 into a 500 on the constraint.
            "minio://bvisionry-media/org/AAAAAAAA-BBBB-CCCC-DDDD-EEEEEEEEEEEE/branding/x-logo.png",
            // no scheme / wrong scheme — never resolvable, never storable
            "https://evil.example.com/logo.png",
            "org/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/branding/x-logo.png",
            // bucket omitted, so `org` would be read as the bucket
            "minio://org/aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee/branding/x-logo.png",
    })
    void refusesEveryMarkerThatIsNotThisOrgsOwnBrandingObject(String marker) {
        assertThatThrownBy(() -> service.update(ORG, new UpdateBrandingRequest(null, marker)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    @Test
    void acceptsThisOrgsOwnMarker() {
        String marker = ownMarker();

        BrandingResponse response = service.update(ORG, new UpdateBrandingRequest("#0A5CFF", marker));

        assertThat(response.logoMarker()).isEqualTo(marker);
        assertThat(org.getBrandLogoMarker()).isEqualTo(marker);
    }

    // ---------------------------------------------------------------- colour

    @Test
    void lowercasesAndTrimsTheBrandColour() {
        service.update(ORG, new UpdateBrandingRequest("  #0A5CFF  ", null));

        assertThat(org.getBrandColor()).isEqualTo("#0a5cff");
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "0a5cff",              // no hash
            "#0a5cf",              // five digits
            "#0a5cfff",            // seven digits
            "#0a5czz",             // not hex
            "red",                 // css keyword — would be valid CSS, is not a value we accept
            "#000;} :root{--primary:red", // the CSS-injection attempt this pattern exists for
    })
    void refusesAnythingThatIsNotASixDigitHexColour(String colour) {
        assertThatThrownBy(() -> service.update(ORG, new UpdateBrandingRequest(colour, null)))
                .isInstanceOf(BadRequestException.class);
        verify(repository, never()).save(any());
    }

    // -------------------------------------------------------------- clearing

    @Test
    void clearingBothFieldsReturnsTheOrgToTheDefaultTheme() {
        org.setBrandColor("#0a5cff");
        org.setBrandLogoMarker(ownMarker());

        BrandingResponse cleared = service.update(ORG, new UpdateBrandingRequest(null, null));

        assertThat(cleared.brandColor()).isNull();
        assertThat(cleared.logoMarker()).isNull();
        assertThat(cleared.logoUrl()).isNull();
        assertThat(org.getBrandColor()).isNull();
        assertThat(org.getBrandLogoMarker()).isNull();
        // No marker means no presign attempt at all.
        verify(mediaUrls, never()).resolveUrl(any());
    }

    /** Blank strings are how an HTML form clears a field; treat them as null. */
    @Test
    void blankStringsClearRatherThanStoreEmptyValues() {
        org.setBrandColor("#0a5cff");
        org.setBrandLogoMarker(ownMarker());

        service.update(ORG, new UpdateBrandingRequest("   ", "  "));

        assertThat(org.getBrandColor()).isNull();
        assertThat(org.getBrandLogoMarker()).isNull();
    }

    @Test
    void readPresignsTheStoredMarker() {
        String marker = ownMarker();
        org.setBrandColor("#0a5cff");
        org.setBrandLogoMarker(marker);
        when(mediaUrls.resolveUrl(marker)).thenReturn("http://minio.test/signed?X-Amz-Signature=abc");

        BrandingResponse response = service.get(ORG);

        assertThat(response.brandColor()).isEqualTo("#0a5cff");
        assertThat(response.logoMarker()).isEqualTo(marker);
        assertThat(response.logoUrl()).isEqualTo("http://minio.test/signed?X-Amz-Signature=abc");
    }

    // ----------------------------------------------------------------- audit

    @Test
    void aRealBrandingChangeIsAudited() {
        service.update(ORG, new UpdateBrandingRequest("#0A5CFF", ownMarker()));

        verify(auditLogger).log(eq(ACTOR), eq(ORG),
                eq(OrgAuditActions.ORGANIZATION_BRANDING_UPDATED),
                eq(OrgAuditActions.ENTITY_ORGANIZATION), eq(ORG), detailsCaptor.capture());
        // The colour is recorded; the MARKER is not — an audit row is read more
        // casually than the column, and the marker is a presignable object key.
        assertThat(detailsCaptor.getValue())
                .containsEntry("brandColor", "#0a5cff")
                .containsEntry("logo", "set");
        assertThat(detailsCaptor.getValue().values()).doesNotContain(org.getBrandLogoMarker());
    }

    @Test
    void clearingIsAuditedAsRemoval() {
        org.setBrandColor("#0a5cff");
        org.setBrandLogoMarker(ownMarker());

        service.update(ORG, new UpdateBrandingRequest(null, null));

        verify(auditLogger).log(any(), any(), any(), any(), any(), detailsCaptor.capture());
        assertThat(detailsCaptor.getValue())
                .containsEntry("brandColor", "default")
                .containsEntry("logo", "removed");
    }

    /** A no-op save must not fill the org's activity feed with empty entries. */
    @Test
    void aSaveThatChangesNothingIsNotAudited() {
        org.setBrandColor("#0a5cff");
        service.update(ORG, new UpdateBrandingRequest("#0a5cff", null));

        verify(auditLogger, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void aRefusedWriteIsNeverAudited() {
        assertThatThrownBy(() -> service.update(
                ORG, new UpdateBrandingRequest(null, "minio://bvisionry-media/pdf/x.pdf")))
                .isInstanceOf(BadRequestException.class);

        verify(auditLogger, never()).log(any(), any(), any(), any(), any(), any());
    }

    @Test
    void anOrgWithNoBrandingReadsAsAllNulls() {
        assertThat(service.get(ORG)).isEqualTo(new BrandingResponse(null, null, null));
        verify(mediaUrls, never()).resolveUrl(any());
    }

    // ------------------------------------------------- quota reconciliation
    //
    // This write is the ONLY point the branding flow offers to act on an
    // org-scoped upload again after it lands (no dedicated finalize endpoint —
    // see OrgStorageQuotaService's javadoc), so it is where CONSUME-time quota
    // reconciliation has to hang. These prove the wiring: reconciled exactly
    // when a NEW, non-null marker is being persisted, and a refusal there
    // blocks the write the same way every other refusal in this class does.

    @Test
    void settingANewMarkerReconcilesQuotaBeforePersisting() {
        String marker = ownMarker();

        service.update(ORG, new UpdateBrandingRequest("#0A5CFF", marker));

        verify(mediaQuota).reconcileAfterUpload(ORG, marker);
    }

    @Test
    void clearingTheLogoNeverConsultsQuota() {
        org.setBrandLogoMarker(ownMarker());

        service.update(ORG, new UpdateBrandingRequest(null, null));

        verify(mediaQuota, never()).reconcileAfterUpload(any(), any());
    }

    /** Re-saving the branding form untouched must not re-charge the same object against quota. */
    @Test
    void resavingTheSameMarkerNeverReconciles() {
        String marker = ownMarker();
        org.setBrandLogoMarker(marker);

        service.update(ORG, new UpdateBrandingRequest("#0a5cff", marker));

        verify(mediaQuota, never()).reconcileAfterUpload(any(), any());
    }

    @Test
    void aQuotaRefusalBlocksTheWriteBeforeItIsPersistedOrAudited() {
        String marker = ownMarker();
        doThrow(new IllegalOperationException("over quota"))
                .when(mediaQuota).reconcileAfterUpload(eq(ORG), eq(marker));

        assertThatThrownBy(() -> service.update(ORG, new UpdateBrandingRequest("#0A5CFF", marker)))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("over quota");

        verify(repository, never()).save(any());
        verify(auditLogger, never()).log(any(), any(), any(), any(), any(), any());
        assertThat(org.getBrandLogoMarker()).isNull();
    }
}
