package com.bvisionry.auth;

import com.bvisionry.auth.dto.DownloadTokenResponse;
import com.bvisionry.auth.entity.User;
import com.bvisionry.auth.jwt.JwtProvider;
import com.bvisionry.auth.jwt.TokenType;
import com.bvisionry.common.enums.UserRole;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

class AuthControllerDownloadTokenTest {

    private JwtProvider jwtProvider;

    @BeforeEach
    void setUp() {
        jwtProvider = new JwtProvider(
                "test-secret-key-must-be-32-bytes-minimum-for-hmac-sha-256!!",
                60_000L, 60_000L, 60_000L, "bvisionry-api", "bvisionry-app");
    }

    @Test
    void downloadToken_returnsValidJwtAndBaseUrlAndTtl() {
        User user = new User();
        user.setId(UUID.randomUUID());
        user.setEmail("ada@example.com");
        user.setRole(UserRole.MEMBER);

        AuthController controller = new AuthController(
                /* authService */ null,
                /* passwordResetService */ null,
                /* rateLimitService */ null,
                /* clientIpResolver */ null,
                /* cookieService */ null,
                jwtProvider,
                "https://example.test");

        var response = controller.downloadToken(user);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        DownloadTokenResponse body = response.getBody();
        assertThat(body).isNotNull();
        assertThat(body.baseUrl()).isEqualTo("https://example.test");
        assertThat(body.expiresInSeconds()).isEqualTo(60L);
        assertThat(jwtProvider.validateToken(body.token(), TokenType.DOWNLOAD)).isTrue();
        assertThat(jwtProvider.getUserIdFromToken(body.token())).isEqualTo(user.getId());
    }

    @Test
    void downloadToken_returns401WhenNoPrincipal() {
        AuthController controller = new AuthController(null, null, null, null, null, jwtProvider, "https://example.test");
        var response = controller.downloadToken(null);
        assertThat(response.getStatusCode().value()).isEqualTo(401);
    }
}
