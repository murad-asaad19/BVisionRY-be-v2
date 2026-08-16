package com.bvisionry.media;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.web.multipart.MultipartFile;

import com.bvisionry.common.exception.BadRequestException;
import com.sun.net.httpserver.HttpServer;

import io.minio.BucketExistsArgs;
import io.minio.GetPresignedObjectUrlArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * Validation contract of the media upload + external-fetch paths:
 *
 * <ul>
 *   <li>Server-proxied uploads and browser-direct presigns enforce a per-kind
 *       content-type allowlist — a caller can never store {@code text/html},
 *       an executable, or an unknown {@code kind} folder.</li>
 *   <li>Server-proxied uploads enforce per-kind size caps (the presign path
 *       cannot — a presigned PUT does not bind Content-Length — which is why
 *       the caps here only gate the multipart path).</li>
 *   <li>External http(s) fetches refuse private / loopback / link-local hosts
 *       (SSRF) and abort as soon as the byte ceiling is crossed instead of
 *       buffering the whole body first (OOM).</li>
 * </ul>
 */
class MediaServiceValidationTest {

    private MinioClient internalClient;
    private MinioClient publicClient;
    private MediaProperties props;
    private MediaService service;
    private HttpServer server;

    @BeforeEach
    void setUp() throws Exception {
        internalClient = mock(MinioClient.class);
        publicClient = mock(MinioClient.class);
        props = new MediaProperties();
        lenient().when(internalClient.bucketExists(any(BucketExistsArgs.class))).thenReturn(true);
        // Every case in this file uploads with orgId == null (platform path), which
        // never consults quota — an unstubbed mock is fine, it's simply never invoked.
        service = new MediaService(internalClient, publicClient, props,
                mock(OrgStorageQuotaService.class),
                () -> new com.bvisionry.common.security.CurrentUser(
                java.util.UUID.randomUUID(), null, "Test", "SUPER_ADMIN"));
    }

    @AfterEach
    void tearDown() {
        if (server != null) {
            server.stop(0);
        }
    }

    // -------------------------------------------------------------------------
    // Server-proxied upload validation
    // -------------------------------------------------------------------------

    @Test
    void uploadRejectsDisallowedContentType() throws Exception {
        MultipartFile file = multipart("evil.html", "text/html", 64);

        assertThatThrownBy(() -> service.upload(file, "asset", null))
                .isInstanceOf(BadRequestException.class)
                .hasMessageContaining("text/html");
        verify(internalClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void uploadRejectsUnknownKind() throws Exception {
        MultipartFile file = multipart("a.png", "image/png", 64);

        assertThatThrownBy(() -> service.upload(file, "../../etc", null))
                .isInstanceOf(BadRequestException.class);
        verify(internalClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void uploadRejectsOversizeForKind() throws Exception {
        // image cap is 10MB — 11MB must be refused before any bytes reach MinIO
        MultipartFile file = multipart("big.png", "image/png", 11L * 1024 * 1024);

        assertThatThrownBy(() -> service.upload(file, "image", null))
                .isInstanceOf(BadRequestException.class);
        verify(internalClient, never()).putObject(any(PutObjectArgs.class));
    }

    @Test
    void uploadAcceptsValidImage() throws Exception {
        MultipartFile file = multipart("logo.png", "image/png", 1024);

        String marker = service.upload(file, "image", null);

        assertThat(marker).startsWith("minio://" + props.getBucket() + "/image/");
        verify(internalClient).putObject(any(PutObjectArgs.class));
    }

    @Test
    void uploadNormalizesContentTypeParameters() throws Exception {
        MultipartFile file = multipart("doc.pdf", "application/pdf; charset=UTF-8", 1024);

        assertThat(service.upload(file, "pdf", null))
                .startsWith("minio://" + props.getBucket() + "/pdf/");
    }

    // -------------------------------------------------------------------------
    // Presign validation (content type + kind only — size is unenforceable)
    // -------------------------------------------------------------------------

    @Test
    void presignRejectsDisallowedContentType() throws Exception {
        assertThatThrownBy(() -> service.presignUpload("pdf", "tool.exe", "application/x-msdownload", null))
                .isInstanceOf(BadRequestException.class);
        verify(publicClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void presignRejectsUnknownKind() throws Exception {
        assertThatThrownBy(() -> service.presignUpload("secrets", "a.pdf", "application/pdf", null))
                .isInstanceOf(BadRequestException.class);
        verify(publicClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void presignInfersContentTypeFromExtensionWhenBlank() throws Exception {
        // Browsers commonly submit an empty type for .m3u8 — the extension fallback
        // keeps the HLS upload flow working while staying allowlisted.
        when(publicClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://minio.test/presigned");

        MediaService.PresignedUpload result = service.presignUpload("video", "stream.m3u8", "", null);

        assertThat(result.marker()).startsWith("minio://" + props.getBucket() + "/video/");
    }

    @Test
    void presignRejectsBlankContentTypeWithUnknownExtension() throws Exception {
        assertThatThrownBy(() -> service.presignUpload("asset", "mystery.bin", null, null))
                .isInstanceOf(BadRequestException.class);
        verify(publicClient, never()).getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class));
    }

    @Test
    void presignAcceptsPdf() throws Exception {
        when(publicClient.getPresignedObjectUrl(any(GetPresignedObjectUrlArgs.class)))
                .thenReturn("http://minio.test/presigned");

        MediaService.PresignedUpload result = service.presignUpload("pdf", "guide.pdf", "application/pdf", null);

        assertThat(result.uploadUrl()).isEqualTo("http://minio.test/presigned");
        assertThat(result.marker()).startsWith("minio://" + props.getBucket() + "/pdf/");
    }

    // -------------------------------------------------------------------------
    // External fetch: SSRF guard
    // -------------------------------------------------------------------------

    @ParameterizedTest
    @ValueSource(strings = {
            "http://127.0.0.1/asset.pdf",
            "http://localhost/asset.pdf",
            "http://10.0.0.8/asset.pdf",
            "http://192.168.1.1/asset.pdf",
            "http://169.254.169.254/latest/meta-data",
            "http://[::1]/asset.pdf",
    })
    void fetchExternalRejectsPrivateHosts(String url) {
        assertThatThrownBy(() -> service.fetchBytes(url))
                .isInstanceOf(MediaUploadException.class)
                .hasMessageContaining("private");
    }

    // -------------------------------------------------------------------------
    // External fetch: byte ceiling + redirect handling
    // (allow-private=true so the fixture server on loopback is reachable)
    // -------------------------------------------------------------------------

    @Test
    void fetchExternalReturnsBodyUnderCap() throws Exception {
        props.setExternalFetchAllowPrivate(true);
        props.setExternalFetchMaxBytes(1024);
        byte[] payload = "hello media".getBytes(StandardCharsets.UTF_8);
        String url = startServer(exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        assertThat(service.fetchBytes(url)).isEqualTo(payload);
    }

    @Test
    void fetchExternalAbortsWhenBodyExceedsCap() throws Exception {
        props.setExternalFetchAllowPrivate(true);
        props.setExternalFetchMaxBytes(1024);
        byte[] payload = new byte[4096];
        String url = startServer(exchange -> {
            exchange.sendResponseHeaders(200, payload.length);
            try (OutputStream os = exchange.getResponseBody()) {
                os.write(payload);
            }
        });

        assertThatThrownBy(() -> service.fetchBytes(url))
                .isInstanceOf(MediaUploadException.class)
                .hasMessageContaining("limit");
    }

    @Test
    void fetchExternalFollowsRedirectToAllowedHost() throws Exception {
        props.setExternalFetchAllowPrivate(true);
        props.setExternalFetchMaxBytes(1024);
        byte[] payload = "after redirect".getBytes(StandardCharsets.UTF_8);
        String url = startServer(exchange -> {
            if (exchange.getRequestURI().getPath().equals("/start")) {
                exchange.getResponseHeaders().add("Location", "/target");
                exchange.sendResponseHeaders(302, -1);
                exchange.close();
            } else {
                exchange.sendResponseHeaders(200, payload.length);
                try (OutputStream os = exchange.getResponseBody()) {
                    os.write(payload);
                }
            }
        });

        assertThat(service.fetchBytes(url + "/start")).isEqualTo(payload);
    }

    @Test
    void fetchExternalRejectsRedirectLoops() throws Exception {
        props.setExternalFetchAllowPrivate(true);
        String url = startServer(exchange -> {
            exchange.getResponseHeaders().add("Location", exchange.getRequestURI().getPath());
            exchange.sendResponseHeaders(302, -1);
            exchange.close();
        });

        assertThatThrownBy(() -> service.fetchBytes(url))
                .isInstanceOf(MediaUploadException.class)
                .hasMessageContaining("redirect");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Boots a loopback fixture server with the given root handler; returns its base URL + "/". */
    private String startServer(com.sun.net.httpserver.HttpHandler handler) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", handler);
        server.start();
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static MultipartFile multipart(String filename, String contentType, long size) throws IOException {
        MultipartFile file = mock(MultipartFile.class);
        lenient().when(file.getOriginalFilename()).thenReturn(filename);
        lenient().when(file.getContentType()).thenReturn(contentType);
        lenient().when(file.getSize()).thenReturn(size);
        lenient().when(file.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[16]));
        return file;
    }
}
