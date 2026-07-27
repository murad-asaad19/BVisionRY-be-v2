package com.bvisionry.auth.sso;

import com.bvisionry.common.audit.AuditLogger;
import com.bvisionry.common.exception.BadRequestException;
import com.bvisionry.common.exception.DuplicateResourceException;
import com.bvisionry.common.exception.ResourceNotFoundException;
import com.bvisionry.common.crypto.SecretEncryptionService;
import com.bvisionry.common.security.CurrentUserAccessor;
import lombok.RequiredArgsConstructor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * Platform-admin CRUD over {@link SsoRegistration}, plus the domain -> registration
 * lookup the login entry point uses.
 *
 * <p>Registrations are PLATFORM-managed (SUPER_ADMIN), never org-self-serve. The
 * reason is the one invariant that cannot be recovered from: {@code emailDomain}
 * asserts "this customer controls every mailbox at this domain", and a tenant that
 * could verify its own domain could claim someone else's. Self-serve needs an
 * automated DNS challenge; until that exists, verification is a human step and this
 * API is the record of it.
 */
@Service
@RequiredArgsConstructor
public class SsoRegistrationService {

    private static final Logger log = LoggerFactory.getLogger(SsoRegistrationService.class);

    /** Action constants for the audit trail below; local because this is auth's surface, not the org feature's. */
    static final String SSO_REGISTRATION_CREATED = "SSO_REGISTRATION_CREATED";
    static final String SSO_REGISTRATION_UPDATED = "SSO_REGISTRATION_UPDATED";
    static final String SSO_REGISTRATION_DELETED = "SSO_REGISTRATION_DELETED";
    static final String ENTITY_SSO_REGISTRATION = "SsoRegistration";

    private final SsoRegistrationRepository repository;
    private final AuditLogger auditLogger;
    private final CurrentUserAccessor currentUser;
    private final SecretEncryptionService secretCipher;

    /**
     * One-shot conversion of client secrets stored before {@code V155} made this
     * column encrypted. Idempotent: a row that already carries a key-version stamp is
     * skipped, so every boot after the first does one indexed-free scan of a table
     * with one row per enterprise customer and writes nothing.
     *
     * <p>Runs here rather than in the migration because the encryption key is an
     * environment variable and Flyway cannot reach it, and eagerly at startup rather
     * than lazily on read because the read happens on the anonymous OIDC handshake
     * path — which has no transaction and must not write. See V155's header.
     *
     * <p>A failure must not stop the application: the reader still handles an
     * unstamped value, so the worst case is that the rows stay plaintext and say so in
     * the log, which is exactly the state before this change. Refusing to boot would
     * turn a data-hygiene problem into an outage.
     *
     * <p><b>NOT {@code @Transactional}, and that is what makes the sentence above
     * true.</b> With a transaction on this method the {@code catch} below would sit
     * INSIDE the boundary: a JPA failure marks the transaction rollback-only, the
     * swallowed exception then re-surfaces as {@code UnexpectedRollbackException} when
     * the proxy commits — after this method has returned — and an exception escaping an
     * {@code ApplicationReadyEvent} listener fails {@code SpringApplication.run}. The
     * comment would have promised availability the code did not deliver. Without it the
     * read runs in its own transaction and returns detached entities, {@code saveAll}
     * opens its own (and is atomic per call), and the catch genuinely contains the
     * failure. The mutation below is therefore an explicit save, never dirty-checking.
     */
    @EventListener(ApplicationReadyEvent.class)
    public void encryptLegacyPlaintextSecrets() {
        try {
            List<SsoRegistration> legacy = repository.findByOidcClientSecretIsNotNull().stream()
                    .filter(r -> !SecretEncryptionService.isVersioned(r.getOidcClientSecret()))
                    .toList();
            if (legacy.isEmpty()) {
                return;
            }
            legacy.forEach(r -> r.setOidcClientSecret(secretCipher.encrypt(r.getOidcClientSecret())));
            repository.saveAll(legacy);
            log.info("Encrypted {} SSO registration client secret(s) that predated encryption at rest",
                    legacy.size());
        } catch (RuntimeException e) {
            // No secret in the message, and none in the exception either — it carries
            // the cipher's own text, never the value.
            log.error("Could not encrypt pre-existing SSO client secrets; they remain in plaintext "
                    + "at rest and enterprise sign-in is unaffected. Cause: {}", e.toString());
        }
    }

    @Transactional(readOnly = true)
    public List<SsoRegistrationResponse> list() {
        return repository.findAllByOrderByEmailDomainAsc().stream()
                .map(SsoRegistrationResponse::from)
                .toList();
    }

    @Transactional
    public SsoRegistrationResponse create(SsoRegistrationRequest request) {
        SsoRegistration registration = new SsoRegistration();
        apply(registration, request, null);
        SsoRegistration saved = repository.save(registration);
        audit(SSO_REGISTRATION_CREATED, saved);
        return SsoRegistrationResponse.from(saved);
    }

    @Transactional
    public SsoRegistrationResponse update(UUID id, SsoRegistrationRequest request) {
        SsoRegistration registration = requireRegistration(id);
        apply(registration, request, id);
        SsoRegistration saved = repository.save(registration);
        audit(SSO_REGISTRATION_UPDATED, saved);
        return SsoRegistrationResponse.from(saved);
    }

    @Transactional
    public void delete(UUID id) {
        SsoRegistration registration = requireRegistration(id);
        // Captured before the delete: after it the entity is gone and the trail would
        // record an id nobody can resolve back to a domain.
        audit(SSO_REGISTRATION_DELETED, registration);
        repository.delete(registration);
    }

    /**
     * Which identity provider may speak for which email domain, and who decided.
     *
     * <p>This is the highest-value write surface in the feature — changing
     * {@code emailDomain} changes whose accounts a customer's IdP can reach — so it
     * must leave a trail. The row is keyed to the registration's org so the entry
     * lands in that tenant's activity feed rather than nowhere.
     *
     * <p>{@code oidcClientSecret} is never included. An audit row is read by more
     * people, and kept longer, than almost anything else in the system.
     */
    private void audit(String action, SsoRegistration registration) {
        auditLogger.log(currentUser.require().userId(), registration.getOrgId(), action,
                ENTITY_SSO_REGISTRATION, registration.getId(),
                Map.of("registrationId", registration.getRegistrationId(),
                        "orgId", registration.getOrgId().toString(),
                        "emailDomain", registration.getEmailDomain(),
                        "protocol", registration.getProtocol().name(),
                        "enabled", registration.isEnabled()));
    }

    /**
     * Which enabled registration owns this email's domain, if any.
     *
     * <p>Both sides go through {@link EmailDomains}: the stored domain was
     * normalised on write, the asserted one is normalised here, so the lookup is
     * exact-label equality on one canonical form. A domain that will not normalise
     * simply resolves to nothing.
     */
    @Transactional(readOnly = true)
    public Optional<SsoRegistration> findByEmail(String email) {
        return EmailDomains.domainOf(email).flatMap(repository::findByEmailDomainAndEnabledTrue);
    }

    private void apply(SsoRegistration registration, SsoRegistrationRequest request, UUID existingId) {
        String domain = EmailDomains.normalizeDomain(request.emailDomain())
                .orElseThrow(() -> new BadRequestException(
                        "emailDomain is not a valid domain name: " + request.emailDomain()));

        requireUnique(repository.findByRegistrationId(request.registrationId()), existingId,
                "registrationId", request.registrationId());
        // The DB UNIQUE index is the real guarantee (it also covers direct SQL and
        // concurrent writers); this check exists so the common case answers 409 with
        // the offending value instead of a 500 carrying a constraint name.
        requireUnique(repository.findByEmailDomain(domain), existingId, "emailDomain", domain);

        registration.setRegistrationId(request.registrationId());
        registration.setOrgId(request.orgId());
        registration.setProtocol(request.protocol());
        registration.setEmailDomain(domain);
        registration.setDisplayName(request.displayName());
        registration.setEnabled(request.enabled());

        if (request.protocol() == SsoRegistration.Protocol.SAML) {
            if (isBlank(request.samlMetadata())) {
                throw new BadRequestException("samlMetadata is required for a SAML registration");
            }
            registration.setSamlMetadata(request.samlMetadata());
            registration.setOidcIssuerUri(null);
            registration.setOidcClientId(null);
            registration.setOidcClientSecret(null);
        } else {
            if (isBlank(request.oidcIssuerUri()) || isBlank(request.oidcClientId())
                    || isBlank(request.oidcClientSecret())) {
                throw new BadRequestException(
                        "oidcIssuerUri, oidcClientId and oidcClientSecret are required for an OIDC registration");
            }
            registration.setOidcIssuerUri(request.oidcIssuerUri());
            registration.setOidcClientId(request.oidcClientId());
            // The ONLY place a client secret is written. Ciphertext from here on: the
            // column, a database dump and every backup hold an AES-256-GCM value under
            // a key that lives in the deploy environment, not in this database.
            registration.setOidcClientSecret(secretCipher.encrypt(request.oidcClientSecret()));
            registration.setSamlMetadata(null);
        }
    }

    private static void requireUnique(Optional<SsoRegistration> found, UUID existingId,
                                      String field, String value) {
        if (found.isPresent() && !found.get().getId().equals(existingId)) {
            throw new DuplicateResourceException(
                    "Another SSO registration already uses " + field + " " + value);
        }
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    /**
     * Named {@code require*} because {@link SsoRegistration} carries an
     * {@code orgId} field, which makes this repository org-owned to the ArchUnit
     * tenancy rule and confines bare-ID loads to guard methods. There is no org
     * predicate to add here — a registration is platform-scoped and only
     * SUPER_ADMIN reaches this class — so the guard is existence only.
     */
    private SsoRegistration requireRegistration(UUID id) {
        return repository.findById(id)
                .orElseThrow(() -> new ResourceNotFoundException("SsoRegistration", id.toString()));
    }
}
