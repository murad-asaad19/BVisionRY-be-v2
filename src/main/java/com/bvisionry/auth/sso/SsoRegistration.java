package com.bvisionry.auth.sso;

import com.bvisionry.common.entity.BaseEntity;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.util.UUID;

/**
 * One enterprise identity provider the platform trusts, bound to one verified
 * email domain and one organization.
 *
 * <p>{@code orgId} is a bare UUID rather than a {@code @ManyToOne} to the
 * organization aggregate. That is not laziness about modelling: the ArchUnit
 * ratchet forbids a new {@code auth -> organization} type edge, and this row only
 * ever needs the tenant's identity, never its state. Referential integrity is the
 * FK in V152; the precedent is {@code coaching.domain.CoachAssignment}.
 */
@Entity
@Table(name = "sso_registrations")
@Getter
@Setter
@NoArgsConstructor
public class SsoRegistration extends BaseEntity {

    /** URL-path slug: appears in the ACS location / OIDC redirect_uri the customer configures. */
    @Column(name = "registration_id", nullable = false, unique = true, length = 64)
    private String registrationId;

    @Column(name = "org_id", nullable = false)
    private UUID orgId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 8)
    private Protocol protocol;

    /** Platform-verified, already normalised by {@link EmailDomains#normalizeDomain}. */
    @Column(name = "email_domain", nullable = false, unique = true, length = 253)
    private String emailDomain;

    @Column(name = "display_name", nullable = false, length = 128)
    private String displayName;

    @Column(nullable = false)
    private boolean enabled = true;

    /** SAML only: the IdP's EntityDescriptor XML, including its signing certificate. */
    @Column(name = "saml_metadata", columnDefinition = "text")
    private String samlMetadata;

    @Column(name = "oidc_issuer_uri", length = 512)
    private String oidcIssuerUri;

    @Column(name = "oidc_client_id", length = 255)
    private String oidcClientId;

    /**
     * ENCRYPTED AT REST, and this field holds the CIPHERTEXT — never the secret the
     * customer's identity provider issued. Written through
     * {@code SecretEncryptionService#encrypt} by {@link SsoRegistrationService} and
     * read back by {@link OidcClientRegistrations}, which is the only consumer.
     *
     * <p>1024 rather than 512 because AES-GCM + Base64 + the key-version stamp expands
     * a 512-character secret to roughly 720; see {@code V155}. Rows written before
     * V155 hold PLAINTEXT with no version stamp — {@code SsoRegistrationService}
     * converts them once at startup, and the reader tolerates one until it does.
     *
     * <p>Never leaves the backend either: {@link SsoRegistrationResponse} does not
     * carry it, {@link SsoRegistrationRequest#toString()} redacts it, and the audit
     * trail omits it. Encryption is the layer BELOW those, for a database dump.
     */
    @Column(name = "oidc_client_secret", length = 1024)
    private String oidcClientSecret;

    public enum Protocol {
        SAML,
        OIDC
    }
}
