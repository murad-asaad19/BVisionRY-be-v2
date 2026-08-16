-- =============================================================================
-- V152__sso_registrations.sql — one row per enterprise IdP the platform trusts.
-- =============================================================================
-- Expand-only: one new table, one index. Nothing dropped, renamed or narrowed.
--
-- WHO OWNS THIS ROW. The registration is auth-owned (com.bvisionry.auth.sso) and
-- carries `org_id` as a BARE UUID, deliberately not a JPA association: the
-- ArchUnit ratchet forbids a new auth -> organization type edge, and this table
-- needs the tenant identity, not the tenant aggregate. The FK below is a SCHEMA
-- dependency on organizations, not a Java one — same shape V151 uses for users /
-- course / submissions.
--
-- THE DOMAIN IS THE WHOLE SECURITY MODEL. `email_domain` is a domain the PLATFORM
-- has verified the customer owns; it is what lets an assertion from a customer-run
-- IdP name a user at all. Hence:
--   * UNIQUE — two registrations may never claim the same domain, or either
--     tenant's IdP could assert the other's users. This is the constraint that
--     makes cross-tenant takeover a 23505 rather than a judgement call in Java.
--   * stored ALREADY NORMALISED — lowercased and IDN/punycode-encoded by
--     SsoRegistrationService before it ever reaches here, so the UNIQUE index
--     compares the same form the login path compares. A homoglyph domain
--     ("оrgb.com" with a Cyrillic о) normalises to xn--rgb-red.com and is a
--     DIFFERENT row, never a near-miss.
-- Matching is exact-label equality on that normalised form (never a suffix test),
-- so a subdomain is its own registration. See EmailDomains.
--
-- NO enforcement flag, deliberately. There is no "require SSO / disable password
-- login" column and there must not be one: password and Google sign-in keep
-- working for every user, and turning either off for a tenant is an operator
-- decision, not an agent's.
--
-- SECRETS, AND WHAT THAT COSTS. oidc_client_secret is stored as issued. It is a
-- per-tenant value, so it cannot live in an env var the way the platform's own
-- Google client secret does, and it is never returned by the admin API
-- (SsoRegistrationResponse omits it) nor printed by SsoRegistrationRequest.toString().
--
-- It is NOT encrypted at rest, and this is a REAL exposure, not an equivalent of the
-- other secrets in this database. Password hashes are one-way and refresh tokens are
-- ours to revoke; this is a LIVE credential at the CUSTOMER's identity provider, so a
-- dump of this table lets an attacker authenticate AS US inside the customer's tenant,
-- against a system we do not control and cannot revoke from. Doing better needs a
-- key-management story (rotation, envelope keys) this platform does not have, and a
-- cipher whose key sits in the same config file would be theatre. Encryption at rest
-- is ESCALATED TO THE OPERATOR; rotating a leaked secret means reissuing it at the
-- customer's IdP and updating this row.
-- =============================================================================

CREATE TABLE sso_registrations (
    id                 UUID PRIMARY KEY DEFAULT gen_random_uuid(),
    -- URL-path identity of this registration: it appears in the ACS location and
    -- the OIDC redirect_uri the customer configures at their IdP, so it is
    -- immutable in practice and validated as a slug on the way in.
    registration_id    VARCHAR(64)  NOT NULL,
    org_id             UUID         NOT NULL REFERENCES organizations (id) ON DELETE CASCADE,
    protocol           VARCHAR(8)   NOT NULL,
    -- 253 = the maximum length of a fully qualified domain name in its
    -- punycode/ASCII form, which is the only form ever stored here.
    email_domain       VARCHAR(253) NOT NULL,
    display_name       VARCHAR(128) NOT NULL,
    enabled            BOOLEAN      NOT NULL DEFAULT TRUE,
    -- SAML: the IdP's EntityDescriptor XML, parsed by
    -- RelyingPartyRegistrations.collectionFromMetadata — the documented "the IdP
    -- metadata came from a database" path. Holds the signing certificate, so
    -- editing this row is what rotates the trust anchor.
    saml_metadata      TEXT,
    -- OIDC: discovery does the rest (authorization/token/jwks endpoints).
    oidc_issuer_uri    VARCHAR(512),
    oidc_client_id     VARCHAR(255),
    oidc_client_secret VARCHAR(512),
    created_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),
    updated_at         TIMESTAMP WITH TIME ZONE NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_sso_registrations_registration_id UNIQUE (registration_id),
    -- The cross-tenant takeover guard. See the header.
    CONSTRAINT uq_sso_registrations_email_domain UNIQUE (email_domain),
    -- CEILING: a CHECK pins the protocol set in the schema, so a third protocol
    -- needs DROP CONSTRAINT + re-ADD, which is a CONTRACTION and human-only under
    -- this run's policy. Two protocols are the whole space today and a typo'd
    -- value would otherwise resolve to no adapter at login time.
    CONSTRAINT ck_sso_registrations_protocol CHECK (protocol IN ('SAML', 'OIDC')),
    -- A half-configured registration is worse than none: it resolves at /start,
    -- redirects the user into a handshake, and dies at the IdP with nothing the
    -- user can act on. The service returns 400 first; this is the backstop that
    -- also covers direct SQL.
    CONSTRAINT ck_sso_registrations_protocol_config CHECK (
        (protocol = 'SAML' AND saml_metadata IS NOT NULL)
        OR (protocol = 'OIDC' AND oidc_issuer_uri IS NOT NULL
            AND oidc_client_id IS NOT NULL AND oidc_client_secret IS NOT NULL)
    )
);

-- "which org's registrations are these?" — the only non-unique read: the admin
-- list is small and ordered by domain, and the two login-path reads
-- (by registration_id, by email_domain) are already served by the unique indexes.
CREATE INDEX idx_sso_registrations_org ON sso_registrations (org_id);
