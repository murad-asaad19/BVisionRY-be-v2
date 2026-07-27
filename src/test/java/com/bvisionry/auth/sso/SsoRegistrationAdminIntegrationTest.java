package com.bvisionry.auth.sso;

import com.bvisionry.auth.entity.User;
import com.bvisionry.common.crypto.SecretEncryptionService;
import com.bvisionry.common.enums.UserRole;
import com.bvisionry.common.enums.UserStatus;
import com.bvisionry.testsupport.AbstractPostgresIntegrationTest;
import com.bvisionry.testsupport.EnabledIfDockerAvailable;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.csrf;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Platform administration of SSO registrations, end to end against a real schema.
 *
 * <p>The database constraints are half the design here, so mocking the repository
 * would test the wrong layer: the UNIQUE on {@code email_domain} is what makes
 * "two tenants claim one domain" impossible rather than merely discouraged, and
 * the normalisation on write is what makes that index compare the same form the
 * login path compares. Both are exercised through the HTTP surface.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@EnabledIfDockerAvailable
class SsoRegistrationAdminIntegrationTest extends AbstractPostgresIntegrationTest {

    private static final String PATH = "/api/admin/sso-registrations";

    @Autowired private MockMvc mockMvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private SecretEncryptionService secretCipher;
    @Autowired private SsoRegistrationService ssoRegistrationService;

    /** Local instance: this app exposes no ObjectMapper bean, and none is needed to shape a request body. */
    private final ObjectMapper json = new ObjectMapper();

    private UUID orgId;
    private UUID actorId;

    @BeforeEach
    void seedOrganization() {
        jdbc.update("DELETE FROM sso_registrations");
        orgId = UUID.randomUUID();
        jdbc.update("INSERT INTO organizations (id, name, is_active) VALUES (?, 'OrgB', TRUE)", orgId);

        // A REAL row, not a synthetic principal id: audit_logs.actor_id carries an FK
        // to users(id), so every write below genuinely lands an audit entry — and an
        // actor who does not exist fails the insert. That is the point: it means these
        // tests cannot pass if the audit trail silently stops being written.
        actorId = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO users (id, email, name, role, status, created_at, updated_at)
                VALUES (?, ?, 'Platform', 'SUPER_ADMIN', 'ACTIVE', NOW(), NOW())
                """, actorId, "platform-" + actorId + "@route.invalid");
    }

    private Authentication superAdmin() {
        User user = new User();
        user.setId(actorId);
        user.setEmail("platform@route.invalid");
        user.setName("Platform");
        user.setRole(UserRole.SUPER_ADMIN);
        user.setStatus(UserStatus.ACTIVE);
        return new UsernamePasswordAuthenticationToken(user, null,
                List.of(new SimpleGrantedAuthority(UserRole.SUPER_ADMIN.name())));
    }

    private Map<String, Object> samlBody(String registrationId, String domain) {
        return Map.of(
                "registrationId", registrationId,
                "orgId", orgId.toString(),
                "protocol", "SAML",
                "emailDomain", domain,
                "displayName", "OrgB IdP",
                "enabled", true,
                "samlMetadata", "<EntityDescriptor/>");
    }

    private org.springframework.test.web.servlet.ResultActions create(Map<String, Object> body) throws Exception {
        return mockMvc.perform(post(PATH)
                .with(authentication(superAdmin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON)
                .content(json.writeValueAsString(body)));
    }

    @Test
    void aRegistrationRoundTripsThroughCreateListUpdateAndDelete() throws Exception {
        String id = json.readTree(create(samlBody("orgb-okta", "orgb.com"))
                        .andExpect(status().isCreated())
                        // The secret and the metadata blob have no read path.
                        .andExpect(jsonPath("$.samlConfigured").value(true))
                        .andExpect(jsonPath("$.oidcClientSecret").doesNotExist())
                        .andExpect(jsonPath("$.samlMetadata").doesNotExist())
                        .andReturn().getResponse().getContentAsString())
                .get("id").asText();

        mockMvc.perform(get(PATH).with(authentication(superAdmin())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].emailDomain").value("orgb.com"))
                .andExpect(jsonPath("$[0].registrationId").value("orgb-okta"));

        Map<String, Object> disabled = new java.util.HashMap<>(samlBody("orgb-okta", "orgb.com"));
        disabled.put("enabled", false);
        mockMvc.perform(put(PATH + "/" + id)
                        .with(authentication(superAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(disabled)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.enabled").value(false));

        mockMvc.perform(delete(PATH + "/" + id).with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isNoContent());
        mockMvc.perform(get(PATH).with(authentication(superAdmin())))
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void theDomainIsStoredNormalisedSoTheUniqueIndexAndTheLoginPathCompareTheSameForm() throws Exception {
        create(samlBody("orgb-idn", "  BÜCHER.de  ")).andExpect(status().isCreated())
                .andExpect(jsonPath("$.emailDomain").value("xn--bcher-kva.de"));
        assertThat(jdbc.queryForObject(
                "SELECT email_domain FROM sso_registrations WHERE registration_id = 'orgb-idn'", String.class))
                .isEqualTo("xn--bcher-kva.de");
    }

    @Test
    void aSecondRegistrationCannotClaimAnAlreadyVerifiedDomain() throws Exception {
        // The cross-tenant takeover guard: if two registrations could hold one
        // domain, either tenant's identity provider could speak for the other's users.
        create(samlBody("orgb-okta", "orgb.com")).andExpect(status().isCreated());
        create(samlBody("orgc-okta", "orgb.com")).andExpect(status().isConflict());
        // Including via a different spelling of the same domain.
        create(samlBody("orgc-okta", "ORGB.com.")).andExpect(status().isConflict());
    }

    @Test
    void aDuplicateRegistrationSlugIsRefused() throws Exception {
        create(samlBody("orgb-okta", "orgb.com")).andExpect(status().isCreated());
        create(samlBody("orgb-okta", "orgc.com")).andExpect(status().isConflict());
    }

    @Test
    void aDomainThatIsNotADomainIsRefused() throws Exception {
        create(samlBody("orgb-okta", "not a domain")).andExpect(status().isBadRequest());
        create(samlBody("orgb-okta", "localhost")).andExpect(status().isBadRequest());
    }

    @Test
    void aHalfConfiguredRegistrationIsRefusedRatherThanStored() throws Exception {
        // A registration that resolves at /start and then dies at the provider is
        // worse than no registration: the user is redirected into a dead end.
        Map<String, Object> noMetadata = new java.util.HashMap<>(samlBody("orgb-okta", "orgb.com"));
        noMetadata.remove("samlMetadata");
        create(noMetadata).andExpect(status().isBadRequest());

        create(Map.of("registrationId", "orgb-entra", "orgId", orgId.toString(), "protocol", "OIDC",
                "emailDomain", "orgb.com", "displayName", "OrgB", "enabled", true,
                "oidcIssuerUri", "https://login.microsoftonline.test/t/v2.0"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void anOidcRegistrationKeepsItsSecretOutOfEveryResponse() throws Exception {
        create(Map.of("registrationId", "orgb-entra", "orgId", orgId.toString(), "protocol", "OIDC",
                "emailDomain", "orgb.com", "displayName", "OrgB", "enabled", true,
                "oidcIssuerUri", "https://login.microsoftonline.test/t/v2.0",
                "oidcClientId", "client-abc", "oidcClientSecret", "super-secret"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.oidcClientId").value("client-abc"));

        String listed = mockMvc.perform(get(PATH).with(authentication(superAdmin())))
                .andReturn().getResponse().getContentAsString();
        assertThat(listed).doesNotContain("super-secret");
    }

    @Test
    void aSlugThatCouldNotBeAUrlSegmentIsRefused() throws Exception {
        create(samlBody("../../evil", "orgb.com")).andExpect(status().isBadRequest());
        create(samlBody("Orgb Okta", "orgb.com")).andExpect(status().isBadRequest());
    }

    /**
     * Discovery routes an email domain to the RIGHT protocol's handshake. Sending a
     * SAML tenant down the OIDC path (or the reverse) fails at the identity provider
     * with nothing the user or the operator can read, so the branch is pinned here
     * rather than discovered by an enterprise customer.
     */
    @Test
    void discoveryRoutesEachProtocolToItsOwnHandshakeAndUnknownDomainsToTheLoginPage() throws Exception {
        create(samlBody("orgb-okta", "orgb.com")).andExpect(status().isCreated());
        create(Map.of("registrationId", "orgc-entra", "orgId", orgId.toString(), "protocol", "OIDC",
                "emailDomain", "orgc.com", "displayName", "OrgC", "enabled", true,
                "oidcIssuerUri", "https://login.microsoftonline.test/t/v2.0",
                "oidcClientId", "client-abc", "oidcClientSecret", "super-secret"))
                .andExpect(status().isCreated());

        assertThat(handshakeLocation("founder@orgb.com"))
                .endsWith("/auth/sso/enterprise/saml2/authenticate/orgb-okta");
        assertThat(handshakeLocation("founder@orgc.com"))
                .endsWith("/auth/sso/enterprise/oauth2/authorization/orgc-entra");
        // Case and IDN spelling reach the same registration the login path will.
        assertThat(handshakeLocation("Founder@ORGB.com"))
                .endsWith("/auth/sso/enterprise/saml2/authenticate/orgb-okta");
        // A domain nobody registered, and a near-miss that a suffix test would match.
        assertThat(handshakeLocation("someone@nobody.test")).endsWith("/login?error=sso_no_registration");
        assertThat(handshakeLocation("attacker@evil-orgb.com")).endsWith("/login?error=sso_no_registration");
    }

    @Test
    void aDisabledRegistrationStopsBeingDiscoverable() throws Exception {
        String id = json.readTree(create(samlBody("orgb-okta", "orgb.com"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText();

        Map<String, Object> disabled = new java.util.HashMap<>(samlBody("orgb-okta", "orgb.com"));
        disabled.put("enabled", false);
        mockMvc.perform(put(PATH + "/" + id).with(authentication(superAdmin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(disabled)))
                .andExpect(status().isOk());

        assertThat(handshakeLocation("founder@orgb.com")).endsWith("/login?error=sso_no_registration");
    }

    /** Anonymous, exactly as a signed-out visitor reaches it. */
    private String handshakeLocation(String email) throws Exception {
        return mockMvc.perform(get("/api/auth/sso/handshake/start").param("email", email))
                .andExpect(status().isFound())
                .andReturn().getResponse().getHeader("Location");
    }

    /**
     * Every write to a registration leaves a trail naming the domain it granted.
     *
     * <p>This is the highest-value write surface in the feature: {@code emailDomain}
     * decides whose accounts a customer's identity provider can reach. "Who pointed
     * this IdP at that domain, and when" has to be answerable after the fact.
     */
    @Test
    void everyRegistrationWriteIsAudited() throws Exception {
        jdbc.update("DELETE FROM audit_logs WHERE entity_type = 'SsoRegistration'");

        String id = json.readTree(create(samlBody("orgb-okta", "orgb.com"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString())
                .get("id").asText();
        Map<String, Object> disabled = new java.util.HashMap<>(samlBody("orgb-okta", "orgb.com"));
        disabled.put("enabled", false);
        mockMvc.perform(put(PATH + "/" + id).with(authentication(superAdmin())).with(csrf())
                .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(disabled)))
                .andExpect(status().isOk());
        mockMvc.perform(delete(PATH + "/" + id).with(authentication(superAdmin())).with(csrf()))
                .andExpect(status().isNoContent());

        assertThat(jdbc.queryForList("""
                SELECT action_type FROM audit_logs
                 WHERE entity_type = 'SsoRegistration' AND entity_id = ?
                 ORDER BY occurred_at
                """, String.class, UUID.fromString(id)))
                .containsExactly("SSO_REGISTRATION_CREATED", "SSO_REGISTRATION_UPDATED",
                        "SSO_REGISTRATION_DELETED");

        // The row records WHICH domain and WHO, and never the credential.
        String details = jdbc.queryForObject("""
                SELECT details_json::text FROM audit_logs
                 WHERE entity_type = 'SsoRegistration' AND action_type = 'SSO_REGISTRATION_CREATED'
                """, String.class);
        assertThat(details).contains("orgb.com").contains("orgb-okta").contains("SAML");
        assertThat(jdbc.queryForObject("""
                SELECT actor_id FROM audit_logs
                 WHERE entity_type = 'SsoRegistration' AND action_type = 'SSO_REGISTRATION_CREATED'
                """, UUID.class)).isEqualTo(actorId);
    }

    @Test
    void anOidcSecretNeverReachesTheAuditTrail() throws Exception {
        jdbc.update("DELETE FROM audit_logs WHERE entity_type = 'SsoRegistration'");
        create(Map.of("registrationId", "orgb-entra", "orgId", orgId.toString(), "protocol", "OIDC",
                "emailDomain", "orgb.com", "displayName", "OrgB", "enabled", true,
                "oidcIssuerUri", "https://login.microsoftonline.test/t/v2.0",
                "oidcClientId", "client-abc", "oidcClientSecret", "super-secret"))
                .andExpect(status().isCreated());

        // Audit rows are read by more people, and kept far longer, than almost
        // anything else in the system.
        assertThat(jdbc.queryForObject("""
                SELECT details_json::text FROM audit_logs WHERE entity_type = 'SsoRegistration'
                """, String.class)).doesNotContain("super-secret");
    }

    // ------------------------------------------------- encryption at rest (ruling 3)

    /**
     * The claim is about the DATABASE, so the assertion is about the bytes in the
     * column — not about a mock, and not about the response (which never carried the
     * secret anyway, and would have passed this test before encryption existed).
     */
    @Test
    void anOidcClientSecretIsUnreadableInTheColumnItIsStoredIn() throws Exception {
        create(Map.of("registrationId", "orgb-entra", "orgId", orgId.toString(), "protocol", "OIDC",
                "emailDomain", "orgb.com", "displayName", "OrgB", "enabled", true,
                "oidcIssuerUri", "https://login.microsoftonline.test/t/v2.0",
                "oidcClientId", "client-abc", "oidcClientSecret", "super-secret"))
                .andExpect(status().isCreated());

        String stored = jdbc.queryForObject(
                "SELECT oidc_client_secret FROM sso_registrations WHERE registration_id = 'orgb-entra'",
                String.class);

        assertThat(stored)
                .as("a database dump must not contain the credential")
                .isNotNull()
                .doesNotContain("super-secret")
                .isNotEqualTo("super-secret");
        assertThat(stored)
                .as("stamped with the key version, so a future rotation is a backfill and not data loss")
                .startsWith("v1:");
        // And it is genuinely THIS secret, encrypted — not a truncation, a hash, or a
        // placeholder that happens to differ from the plaintext.
        assertThat(secretCipher.decrypt(stored)).isEqualTo("super-secret");
    }

    /**
     * The V155 backfill: rows written before encryption existed hold plaintext, and
     * Flyway cannot encrypt them because the key is not in the database.
     */
    @Test
    void aPlaintextSecretWrittenBeforeV155IsEncryptedByTheStartupConversion() {
        jdbc.update("""
                INSERT INTO sso_registrations
                    (id, registration_id, org_id, protocol, email_domain, display_name, enabled,
                     oidc_issuer_uri, oidc_client_id, oidc_client_secret)
                VALUES (?, 'legacy-entra', ?, 'OIDC', 'legacy.test', 'Legacy', TRUE,
                        'https://login.microsoftonline.test/t/v2.0', 'client-abc', 'plaintext-secret')
                """, UUID.randomUUID(), orgId);

        ssoRegistrationService.encryptLegacyPlaintextSecrets();

        String stored = jdbc.queryForObject(
                "SELECT oidc_client_secret FROM sso_registrations WHERE registration_id = 'legacy-entra'",
                String.class);
        assertThat(stored).startsWith("v1:").doesNotContain("plaintext-secret");
        assertThat(secretCipher.decrypt(stored))
                .as("converted, not destroyed — the customer's IdP still expects this exact value")
                .isEqualTo("plaintext-secret");

        // Idempotent: a second boot must not encrypt the ciphertext a second time.
        ssoRegistrationService.encryptLegacyPlaintextSecrets();
        assertThat(jdbc.queryForObject(
                "SELECT oidc_client_secret FROM sso_registrations WHERE registration_id = 'legacy-entra'",
                String.class)).isEqualTo(stored);
    }

    /**
     * THE PREDICATE IS LOAD-BEARING, and nothing pinned it.
     *
     * <p>A SAML registration has a NULL {@code oidc_client_secret}. Measured: swapping
     * {@code findByOidcClientSecretIsNotNull()} for {@code findAll()} leaves the whole
     * suite GREEN, because the existing back-fill test seeds one OIDC row into a table
     * its {@code @BeforeEach} just emptied — the mixed-protocol shape, which is the
     * normal production shape, was never exercised. Under that swap a NULL enters the
     * batch, {@code isVersioned(null)} is false, {@code encrypt(null)} throws, and the
     * blanket {@code catch (RuntimeException)} turns the ENTIRE back-fill into a silent
     * no-op logged as a handled error — leaving the real OIDC secret in plaintext for
     * ever while the log says the problem was handled.
     */
    @Test
    void theBackfillIgnoresSamlRowsAndStillConvertsTheOidcOneBesideThem() {
        jdbc.update("""
                INSERT INTO sso_registrations
                    (id, registration_id, org_id, protocol, email_domain, display_name, enabled,
                     saml_metadata)
                VALUES (?, 'legacy-saml', ?, 'SAML', 'saml.test', 'Legacy SAML', TRUE,
                        '<EntityDescriptor/>')
                """, UUID.randomUUID(), orgId);
        jdbc.update("""
                INSERT INTO sso_registrations
                    (id, registration_id, org_id, protocol, email_domain, display_name, enabled,
                     oidc_issuer_uri, oidc_client_id, oidc_client_secret)
                VALUES (?, 'legacy-oidc-beside-saml', ?, 'OIDC', 'oidc.test', 'Legacy OIDC', TRUE,
                        'https://login.microsoftonline.test/t/v2.0', 'client-abc', 'plaintext-beside-saml')
                """, UUID.randomUUID(), orgId);

        ssoRegistrationService.encryptLegacyPlaintextSecrets();

        assertThat(jdbc.queryForObject(
                "SELECT oidc_client_secret FROM sso_registrations WHERE registration_id = 'legacy-oidc-beside-saml'",
                String.class))
                .as("the SAML row's NULL secret must not abort the conversion of the OIDC row beside it")
                .startsWith("v1:");
        assertThat(jdbc.queryForObject(
                "SELECT oidc_client_secret FROM sso_registrations WHERE registration_id = 'legacy-saml'",
                String.class))
                .as("a SAML row has no client secret and must be left exactly as it was")
                .isNull();
    }

    /**
     * V155's ONLY DDL is a column widening, and nothing pinned it: every other test uses
     * a 12-character secret, so reverting the column to VARCHAR(512) leaves the suite
     * green. Measured. But the request DTO validates {@code @Size(max = 512)}, and a
     * 512-character plaintext Base64-expands past 512 once encrypted — so a real Entra
     * or Okta secret at the accepted maximum would 409 on the admin's save in
     * production, which is precisely the failure V155's header claims to prevent.
     */
    @Test
    void aSecretAtTheValidatedMaximumLengthSurvivesEncryptionAndRoundTrips() throws Exception {
        String maxSecret = "s".repeat(512);
        create(Map.of("registrationId", "max-len", "orgId", orgId.toString(), "protocol", "OIDC",
                "emailDomain", "maxlen.test", "displayName", "MaxLen", "enabled", true,
                "oidcIssuerUri", "https://login.microsoftonline.test/t/v2.0",
                "oidcClientId", "client-abc", "oidcClientSecret", maxSecret))
                .andExpect(status().isCreated());

        String stored = jdbc.queryForObject(
                "SELECT oidc_client_secret FROM sso_registrations WHERE registration_id = 'max-len'",
                String.class);
        assertThat(stored).startsWith("v1:");
        assertThat(secretCipher.decrypt(stored)).isEqualTo(maxSecret);
    }

    @Test
    void updatingAMissingRegistrationIsANotFound() throws Exception {
        mockMvc.perform(put(PATH + "/" + UUID.randomUUID())
                        .with(authentication(superAdmin())).with(csrf())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(samlBody("orgb-okta", "orgb.com"))))
                .andExpect(status().isNotFound());
    }
}
