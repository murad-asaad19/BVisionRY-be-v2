package com.bvisionry.auth.sso;

/**
 * Shared SSO test fixtures.
 *
 * <p>Exists for one reason: the IdP metadata below carries a real X.509
 * certificate, and OpenSAML rejects a fake one — so it cannot be inlined per
 * test without copying a 10-line base64 blob around.
 */
final class SsoTestFixtures {

    private SsoTestFixtures() {
    }

    /**
     * A throwaway self-signed RSA certificate generated for these tests. OpenSAML
     * parses and holds it as the verification credential, so an empty or malformed
     * value would not get past {@code collectionFromMetadata}.
     */
    private static final String IDP_CERTIFICATE = """
            MIICzDCCAbSgAwIBAgIJAI1mj9BcdmqyMA0GCSqGSIb3DQEBDAUAMBMxETAPBgNVBAMTCFRlc3QgSWRQMCAXDTI2MDcyNzA0NTkw\
            N1oYDzIxMjYwNzAzMDQ1OTA3WjATMREwDwYDVQQDEwhUZXN0IElkUDCCASIwDQYJKoZIhvcNAQEBBQADggEPADCCAQoCggEBAJvR\
            EB+8a1JbpBK8aM/rpOQiuXmMgn9eeJnNfM1Dm99NRtXixhem/Jqp+YTXDkyDGZn+a/w5GkQsnINSyGG0Tq9vNbkop4KXlsummSZg\
            xInHe6jSLSmrorIhVnJrQY4KxqCwAm0vGDlCNkonmDhQDk3k171FydGY0WdRQJtNb7cQag/oFsKyp411hVWw+5QnoPnllVOXX/gU\
            PaJHAKVRJyA5U9d64JxSbJUkAIYQeOszWEJ3G3QPIsW+4o89dcBw3EVgXDyc0EfBIM9goFfZWnXXxBhH25dh4e8fEpqDz15gEggK\
            si4cRwIjYYvOrEi402g3IbeP1cKOb7oPQYAKigkCAwEAAaMhMB8wHQYDVR0OBBYEFAr2AGTKnjjlyPTWoo/OVtdzMr/qMA0GCSqG\
            SIb3DQEBDAUAA4IBAQAWVdWuc4qvOq3KEJ0aBpBeaRicIlr2dnCncFwytwDfF4YWIxOB1BrkxKjFmkZxKEKtxJk4Fz/a1Qg6sOxu\
            AVznjWDC/H3DVT1rR9ncSHRmsJrzRL0PX1YgdF2m0GJ84BqjiZ2mWwQwmvTVvOzqFbXX12tyHBBg24QU4bGDWxYFPw+zAdePqcYX\
            9Xhr+yBLCnbjBuuOppmItnefyuhchfZ3FZibfR8U8xmUvjitHPArSgGRdG3opLB7ceHwVGP1n0YjnXUWdD2x7aQP7KDX7fntOU0r\
            NFq5MM4bNs7SKdJVpsJpTFhgQNQY8BXdmWFfs8ntdr5PF9A5I1Lrix7t7ssJ""";

    /** A minimal but REAL IdP EntityDescriptor, as an admin would paste it in. */
    static final String IDP_METADATA = """
            <?xml version="1.0" encoding="UTF-8"?>
            <EntityDescriptor xmlns="urn:oasis:names:tc:SAML:2.0:metadata"
                              entityID="https://idp.orgb.test/entity">
              <IDPSSODescriptor WantAuthnRequestsSigned="false"
                                protocolSupportEnumeration="urn:oasis:names:tc:SAML:2.0:protocol">
                <KeyDescriptor use="signing">
                  <KeyInfo xmlns="http://www.w3.org/2000/09/xmldsig#">
                    <X509Data><X509Certificate>%s</X509Certificate></X509Data>
                  </KeyInfo>
                </KeyDescriptor>
                <SingleSignOnService Binding="urn:oasis:names:tc:SAML:2.0:bindings:HTTP-Redirect"
                                     Location="https://idp.orgb.test/sso"/>
              </IDPSSODescriptor>
            </EntityDescriptor>
            """.formatted(IDP_CERTIFICATE);
}
