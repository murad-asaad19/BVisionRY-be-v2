package com.bvisionry.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

/**
 * Guards against any globally-registered serializer that mutates String
 * values in JSON responses. The former {@code XssProtectionConfig} registered
 * a Jackson 2 module that HTML-escaped EVERY string; it was inert after the
 * Boot 4 / Jackson 3 migration, and if a Jackson 3 equivalent were ever
 * (re)introduced it would corrupt data product-wide: {@code &} in MinIO
 * presigned URL query strings breaks signature validation, "R&amp;D"
 * round-trips double-escaped on edit-resubmit, etc. React already escapes on
 * render, and the OWASP sanitizer guards the one HTML-bearing surface (email
 * templates) — output encoding at the API layer is deliberately absent.
 */
@SpringBootTest
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class JsonSerializationIntegrityTest extends AbstractPostgresIntegrationTest {

    /** The context-configured Jackson 3 mapper used by Spring MVC. */
    @Autowired
    private ObjectMapper objectMapper;

    @Test
    void specialCharactersSurviveSerializationUnmodified() {
        String raw = "R&D <script>alert(1)</script> \"quoted\" 'single' 5>3 a&b=c";

        String json = objectMapper.writeValueAsString(raw);

        // JSON string escaping only (quotes/backslashes) — never HTML entities.
        assertThat(json).doesNotContain("&amp;").doesNotContain("&lt;").doesNotContain("&gt;")
                .doesNotContain("&quot;").doesNotContain("&#");
        assertThat(json).contains("R&D").contains("<script>");

        String roundTripped = objectMapper.readValue(json, String.class);
        assertThat(roundTripped).isEqualTo(raw);
    }

    @Test
    void presignedUrlStyleQueryStringsRoundTripExactly() {
        String url = "https://minio.local/bucket/key?X-Amz-Signature=abc123&X-Amz-Expires=900&X-Amz-Date=20260703";

        String roundTripped = objectMapper.readValue(objectMapper.writeValueAsString(url), String.class);

        assertThat(roundTripped).isEqualTo(url);
    }
}
