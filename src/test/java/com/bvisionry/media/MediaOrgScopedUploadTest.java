package com.bvisionry.media;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.springframework.web.multipart.MultipartFile;

import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.IllegalOperationException;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.http.Method;
import io.minio.PutObjectArgs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.atLeastOnce;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * ORG-SCOPED uploads — the half of white-label branding that lives in the media
 * package, i.e. what an ORG_ADMIN can reach now that {@code MediaController}
 * admits one.
 *
 * <p>Three claims, each of which must be able to fail:
 * <ol>
 *   <li><strong>kind is pinned to {@code image}</strong> on BOTH the multipart
 *       and the presign path. Authorization only admits an ORG_ADMIN together
 *       with an {@code orgId}, so "orgId present ⇒ image" is exactly "an
 *       ORG_ADMIN can upload nothing but images".</li>
 *   <li><strong>the object lands under {@code org/&lt;orgId&gt;/branding/}</strong>
 *       — the tenant prefix the organization feature validates a stored marker
 *       against. Without it the IDOR guard has nothing to check.</li>
 *   <li><strong>Content-Type is bound into the presigned signature</strong>, so
 *       a caller that declared {@code image/png} cannot then PUT
 *       {@code text/html} and have the object store serve stored markup off a
 *       presigned GET.</li>
 * </ol>
 */
class MediaOrgScopedUploadTest {

    private static final UUID ORG = UUID.fromString("11111111-2222-3333-4444-555555555555");

    private MinioClient internalClient;
    private MinioClient publicClient;
    private MediaProperties props;
    private OrgStorageQuotaService orgStorageQuota;
    private MediaService service;

    @BeforeEach
    void setUp() throws Exception {
        internalClient = mock(MinioClient.class);
        publicClient = mock(MinioClient.class);
        props = new MediaProperties();
        // A mocked collaborator, not the real quota engine — its own listing /
        // override-precedence / reconcile behaviour is covered by
        // OrgStorageQuotaServiceTest. Here we only care that MediaService WIRES
        // to it correctly (see the "quota wiring" tests below); an unstubbed
        // void requireCapacity(...) is a no-op, so every other test in this file
        // is unaffected.
        orgStorageQuota = mock(OrgStorageQuotaService.class);
        lenient().when(internalClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        lenient().when(publicClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://minio.test/presigned");
        // A non-coach caller: this file is about the ORG-scoped bound.
        service = new MediaService(internalClient, publicClient, props, orgStorageQuota,
                () -> new com.bvisionry.common.security.CurrentUser(
                java.util.UUID.randomUUID(), null, "Test", "SUPER_ADMIN"));
    }

    // ---------------------------------------------------------------- kind

    @ParameterizedTest
    @ValueSource(strings = {"asset", "pdf", "video"})
    void multipartUploadRefusesAnyOrgScopedKindOtherThanImage(String kind) throws Exception {
        MultipartFile file = multipart("payload.pdf", "application/pdf", 1024);

        assertThatThrownBy(() -> service.upload(file, kind, ORG))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("kind 'image'");
        verify(internalClient, never()).putObject(any(PutObjectArgs.class));
    }

    @ParameterizedTest
    @ValueSource(strings = {"asset", "pdf", "video"})
    void presignRefusesAnyOrgScopedKindOtherThanImage(String kind) throws Exception {
        assertThatThrownBy(() -> service.presignUpload(kind, "payload.pdf", "application/pdf", ORG))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("kind 'image'");
        verify(publicClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    /**
     * A blank kind defaults to {@code asset} on the presign path — which for an
     * org-scoped call must be a refusal, not a silent default that lands a
     * non-image under the branding prefix.
     */
    @Test
    void presignRefusesOrgScopedUploadWithNoKind() throws Exception {
        assertThatThrownBy(() -> service.presignUpload(null, "logo.png", "image/png", ORG))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("kind 'image'");
    }

    /** The kind restriction must not leak onto the platform (orgId-less) paths. */
    @Test
    void platformUploadsAreUnaffected() throws Exception {
        assertThat(service.upload(multipart("guide.pdf", "application/pdf", 1024), "pdf", null))
                .startsWith("minio://" + props.getBucket() + "/pdf/");
        assertThat(service.presignUpload("video", "clip.mp4", "video/mp4", null).marker())
                .startsWith("minio://" + props.getBucket() + "/video/");
    }

    // -------------------------------------------------------- tenant prefix

    @Test
    void multipartUploadLandsUnderTheOrgBrandingPrefix() throws Exception {
        String marker = service.upload(multipart("Logo File.PNG", "image/png", 2048), "image", ORG);

        assertThat(marker)
                .startsWith("minio://" + props.getBucket() + "/org/" + ORG + "/branding/")
                .endsWith("-Logo_File.PNG");

        ArgumentCaptor<PutObjectArgs> put = ArgumentCaptor.forClass(PutObjectArgs.class);
        verify(internalClient).putObject(put.capture());
        assertThat(put.getValue().object()).startsWith("org/" + ORG + "/branding/");
    }

    @Test
    void presignLandsUnderTheOrgBrandingPrefix() throws Exception {
        MediaService.PresignedUpload result =
                service.presignUpload("image", "logo.png", "image/png", 2048L, ORG);

        assertThat(result.marker())
                .startsWith("minio://" + props.getBucket() + "/org/" + ORG + "/branding/");
    }

    /**
     * The prefix has to come from the SERVER'S orgId, not from anything the
     * caller controls, or the guard is decorative: a filename carrying path
     * separators must not be able to walk the key out of the org folder.
     */
    @Test
    void traversalInTheFilenameCannotEscapeTheOrgPrefix() throws Exception {
        String marker = service.upload(
                multipart("../../other-org/steal.png", "image/png", 512), "image", ORG);

        assertThat(marker).startsWith("minio://" + props.getBucket() + "/org/" + ORG + "/branding/");
        assertThat(marker.substring(("minio://" + props.getBucket() + "/org/" + ORG + "/branding/").length()))
                .doesNotContain("/")
                .doesNotContain("..");
    }

    // ------------------------------------------------------ content-type pin

    @Test
    void orgScopedPresignBindsTheNormalizedContentTypeIntoTheSignature() throws Exception {
        MediaService.PresignedUpload result =
                service.presignUpload("image", "logo.PNG", "IMAGE/PNG; charset=binary", 2048L, ORG);

        assertThat(result.contentType()).isEqualTo("image/png");

        assertThat(capturedPutArgs().extraHeaders().get("Content-Type"))
                .as("Content-Type must be a SIGNED header, or the PUT can spoof text/html")
                .containsExactly("image/png");
    }

    /**
     * QUOTA BYPASS. The quota budgets against a CLIENT-DECLARED sizeBytes, and
     * reconciliation only fires if the caller later persists the marker. Without the
     * declared size in the signature, an ORG_ADMIN calling the API directly could
     * presign N times declaring one byte each, PUT arbitrary bytes to every URL, and
     * simply never consume them — the quota never even gets asked.
     *
     * <p>Signing Content-Length is what turns the declaration into an obligation: the
     * object store refuses any PUT whose body is a different length.
     */
    @Test
    void orgScopedPresignBindsTheDeclaredSizeIntoTheSignature() throws Exception {
        service.presignUpload("image", "logo.png", "image/png", 4096L, ORG);

        assertThat(capturedPutArgs().extraHeaders().get("Content-Length"))
                .as("Content-Length must be a SIGNED header, or the declared size the "
                        + "quota was budgeted against is unenforceable")
                .containsExactly("4096");
    }

    /** Platform presigns keep the historical unbound behaviour (see MediaService). */
    @Test
    void platformPresignDoesNotBindContentTypeOrLength() throws Exception {
        service.presignUpload("pdf", "guide.pdf", "application/pdf", null);

        assertThat(capturedPutArgs().extraHeaders().keySet())
                .doesNotContain("Content-Type", "Content-Length");
    }

    /**
     * {@code presignUpload} signs twice — the PUT the caller uploads to, and the
     * GET preview it returns. Only the PUT carries the pinned Content-Type.
     */
    private GetPresignedObjectUrlArgs capturedPutArgs() throws Exception {
        ArgumentCaptor<GetPresignedObjectUrlArgs> args =
                ArgumentCaptor.forClass(GetPresignedObjectUrlArgs.class);
        verify(publicClient, atLeastOnce()).getPresignedObjectUrl(args.capture());
        return args.getAllValues().stream()
                .filter(a -> a.method() == Method.PUT)
                .findFirst()
                .orElseThrow(() -> new AssertionError("no presigned PUT was requested"));
    }

    /** The per-kind allowlist still applies on top: svg/html stay unstorable. */
    @ParameterizedTest
    @ValueSource(strings = {"image/svg+xml", "text/html"})
    void orgScopedUploadStillRefusesMarkupContentTypes(String contentType) throws Exception {
        assertThatThrownBy(() -> service.presignUpload("image", "x.svg", contentType, ORG))
                .isInstanceOf(BadRequestException.class);
        verify(publicClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    // ------------------------------------------------------------- quota wiring
    //
    // The org-scoped quota check itself (usage listing, override precedence,
    // reconcile-and-delete) is OrgStorageQuotaService's own concern, covered by
    // OrgStorageQuotaServiceTest. These tests only prove MediaService WIRES to
    // it correctly: consulted (with the right args) on org-scoped paths only,
    // never on the platform path, and a refusal stops the upload before
    // anything reaches MinIO.

    @Test
    void multipartUploadChecksQuotaWithTheRealFileSizeForOrgScopedUploads() throws Exception {
        service.upload(multipart("logo.png", "image/png", 2048), "image", ORG);

        verify(orgStorageQuota).requireCapacity(ORG, 2048L);
    }

    @Test
    void multipartUploadNeverConsultsQuotaOnThePlatformPath() throws Exception {
        service.upload(multipart("guide.pdf", "application/pdf", 2048), "pdf", null);

        verifyNoInteractions(orgStorageQuota);
    }

    @Test
    void multipartUploadStopsBeforeMinioWhenQuotaRefuses() throws Exception {
        doThrow(new IllegalOperationException("over quota"))
                .when(orgStorageQuota).requireCapacity(eq(ORG), anyLong());

        assertThatThrownBy(() -> service.upload(multipart("logo.png", "image/png", 2048), "image", ORG))
                .isInstanceOf(IllegalOperationException.class)
                .hasMessageContaining("over quota");
        verify(internalClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void presignChecksQuotaWithTheDeclaredSizeForOrgScopedUploads() throws Exception {
        service.presignUpload("image", "logo.png", "image/png", 4096L, ORG);

        verify(orgStorageQuota).requireCapacity(ORG, 4096L);
    }

    @ParameterizedTest
    @ValueSource(longs = {0L, -1L})
    void presignRequiresAPositiveDeclaredSizeForOrgScopedUploads(long badSize) throws Exception {
        assertThatThrownBy(() -> service.presignUpload("image", "logo.png", "image/png", badSize, ORG))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("sizeBytes");
        verifyNoInteractions(orgStorageQuota);
        verify(publicClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void presignWithNoDeclaredSizeIsRefusedForOrgScopedUploads() throws Exception {
        assertThatThrownBy(() -> service.presignUpload("image", "logo.png", "image/png", ORG))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("sizeBytes");
    }

    @Test
    void presignNeverConsultsQuotaOnThePlatformPath() throws Exception {
        service.presignUpload("video", "clip.mp4", "video/mp4", null);

        verifyNoInteractions(orgStorageQuota);
    }

    @Test
    void presignStopsBeforeIssuingAUrlWhenQuotaRefuses() throws Exception {
        doThrow(new IllegalOperationException("over quota"))
                .when(orgStorageQuota).requireCapacity(eq(ORG), anyLong());

        assertThatThrownBy(() ->
                service.presignUpload("image", "logo.png", "image/png", 4096L, ORG))
                .isInstanceOf(IllegalOperationException.class);
        verify(publicClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    // --------------------------------------------------------------- helper

    private static MultipartFile multipart(String name, String contentType, long size) {
        return new MultipartFile() {
            @Override public String getName() { return "file"; }
            @Override public String getOriginalFilename() { return name; }
            @Override public String getContentType() { return contentType; }
            @Override public boolean isEmpty() { return size == 0; }
            @Override public long getSize() { return size; }
            @Override public byte[] getBytes() { return new byte[0]; }
            @Override public InputStream getInputStream() { return new ByteArrayInputStream(new byte[0]); }
            @Override public void transferTo(java.io.File dest) { /* unused */ }
            @Override public void transferTo(java.nio.file.Path dest) { /* unused */ }
            public void transferTo(OutputStream out) { /* unused */ }
        };
    }
}
