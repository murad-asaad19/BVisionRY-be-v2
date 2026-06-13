package com.bvisionry.e2e;

import com.bvisionry.auth.UserRepository;
import com.bvisionry.auth.entity.User;
import com.bvisionry.common.enums.SubscriptionTier;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.organization.OrganizationRepository;
import com.bvisionry.organization.entity.Organization;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * E2E test hooks. <strong>Profile-gated</strong> — these endpoints physically do not
 * exist when {@code spring.profiles.active != e2e}, so they cannot be reached in
 * dev/staging/prod even if a route accidentally leaked through SecurityConfig.
 *
 * <p>Designed to be called from Playwright {@code globalSetup}:
 * <ul>
 *   <li>{@code POST /test/reset} — truncates app tables and reseeds the canonical
 *       three-role fixture (super admin, org admin, member) inside a single org.</li>
 *   <li>{@code POST /test/ai/next-response} — scripts the next FakeChatModel response.</li>
 * </ul>
 */
@RestController
@RequestMapping("/test")
@RequiredArgsConstructor
@Profile("e2e")
public class E2eTestController {

    private static final String DEFAULT_PASSWORD = "admin123";
    private static final String DEFAULT_ORG_NAME = "E2E Org";

    private final JdbcTemplate jdbc;
    private final UserRepository userRepository;
    private final OrganizationRepository organizationRepository;
    private final PasswordEncoder passwordEncoder;
    private final FakeChatResponseRegistry fakeChatResponseRegistry;

    public record SeedUser(String email, String name, UserRole role, UUID id) {}

    public record ResetResponse(UUID orgId, List<SeedUser> users) {}

    public record AiResponseRequest(String json) {}

    /**
     * Truncates every application table (preserving Flyway's history so migrations don't
     * re-run on next startup) and reseeds the canonical fixture. Idempotent — safe to call
     * before every test run from {@code globalSetup}.
     */
    @PostMapping("/reset")
    @Transactional
    public ResetResponse reset() {
        truncateAllExceptFlyway();
        fakeChatResponseRegistry.clear();
        return seedDefaultFixture();
    }

    /**
     * Pushes a JSON body that the next call to {@link FakeChatModel} will return verbatim,
     * bypassing the schema-aware default. Use this when a spec asserts on specific AI output
     * (e.g., "renders the recommendation list when the model returns 5 items").
     */
    @PostMapping("/ai/next-response")
    public void enqueueAiResponse(@RequestBody AiResponseRequest request) {
        fakeChatResponseRegistry.enqueue(request.json());
    }

    /**
     * Discovers tables via information_schema rather than hard-coding a list, so new
     * Flyway migrations don't silently leave stale rows behind. Excludes
     * {@code flyway_schema_history} so Spring's Flyway auto-config doesn't try to re-run
     * V1+ on the next startup.
     */
    private void truncateAllExceptFlyway() {
        List<String> tables = jdbc.queryForList(
                "SELECT tablename FROM pg_tables " +
                        "WHERE schemaname = 'public' AND tablename != 'flyway_schema_history'",
                String.class);
        if (tables.isEmpty()) return;
        String quoted = tables.stream().map(t -> "\"" + t + "\"").reduce((a, b) -> a + ", " + b).orElseThrow();
        jdbc.execute("TRUNCATE TABLE " + quoted + " RESTART IDENTITY CASCADE");
    }

    private ResetResponse seedDefaultFixture() {
        Organization org = new Organization();
        org.setName(DEFAULT_ORG_NAME);
        org.setSubscriptionTier(SubscriptionTier.FREE);
        org.setActive(true);
        org = organizationRepository.save(org);

        User superAdmin = persistUser("admin@bvisionry.com", "Super Admin", UserRole.SUPER_ADMIN, null);
        User orgAdmin = persistUser("orgadmin@bvisionry.com", "Org Admin", UserRole.ORG_ADMIN, org);
        User member = persistUser("member@bvisionry.com", "Member User", UserRole.MEMBER, org);

        return new ResetResponse(org.getId(), List.of(
                new SeedUser(superAdmin.getEmail(), superAdmin.getName(), superAdmin.getRole(), superAdmin.getId()),
                new SeedUser(orgAdmin.getEmail(), orgAdmin.getName(), orgAdmin.getRole(), orgAdmin.getId()),
                new SeedUser(member.getEmail(), member.getName(), member.getRole(), member.getId())));
    }

    private User persistUser(String email, String name, UserRole role, Organization org) {
        User u = new User();
        u.setEmail(email);
        u.setName(name);
        u.setPasswordHash(passwordEncoder.encode(DEFAULT_PASSWORD));
        u.setRole(role);
        u.setStatus(UserStatus.ACTIVE);
        u.setActivatedAt(Instant.now());
        u.setOrganization(org);
        return userRepository.save(u);
    }
}
