package com.bvisionry.auth;

import com.bvisionry.audit.AuditRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Disabled("Integration test — needs Docker for Testcontainers Postgres. "
        + "Skipped on Windows + Docker Desktop; runs on Linux/CI where "
        + "Testcontainers connects to /var/run/docker.sock automatically.")
class AuthServiceLoginAuditTest extends AbstractPostgresIntegrationTest {

    @Autowired MockMvc mockMvc;
    @Autowired UserRepository userRepository;
    @Autowired AuditRepository auditRepository;
    @Autowired PasswordEncoder passwordEncoder;

    @Test
    void login_setsLastLoginAt_andDoesNotEmitLoginAudit() throws Exception {
        User u = new User();
        u.setEmail("login-audit@test.com");
        u.setName("Audit Test");
        u.setRole(UserRole.SUPER_ADMIN);
        u.setStatus(UserStatus.ACTIVE);
        u.setPasswordHash(passwordEncoder.encode("password123"));
        userRepository.save(u);

        mockMvc.perform(post("/api/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"email": "login-audit@test.com", "password": "password123"}
                                """))
                .andExpect(status().isOk());

        User reloaded = userRepository.findById(u.getId()).orElseThrow();
        assertThat(reloaded.getLastLoginAt()).isNotNull();

        // Logins are intentionally NOT audited — they were too noisy in the org
        // Activity feed. lastLoginAt above is what the IDLE attention rule reads.
        assertThat(auditRepository.findAll())
                .noneSatisfy(log -> assertThat(log.getActionType()).isEqualTo("USER_LOGIN"));
    }
}
