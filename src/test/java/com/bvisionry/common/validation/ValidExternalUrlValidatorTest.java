package com.bvisionry.common.validation;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class ValidExternalUrlValidatorTest {

    private final ValidExternalUrlValidator validator = new ValidExternalUrlValidator();

    @Test
    void allowsNullAndBlank() {
        assertThat(validator.isValid(null, null)).isTrue();
        assertThat(validator.isValid("", null)).isTrue();
        assertThat(validator.isValid("   ", null)).isTrue();
    }

    @Test
    void allowsPublicHttps() {
        assertThat(validator.isValid("https://typeform.com/to/abc", null)).isTrue();
        assertThat(validator.isValid("https://forms.google.com/survey", null)).isTrue();
    }

    @Test
    void rejectsNonHttpScheme() {
        assertThat(validator.isValid("ftp://example.com", null)).isFalse();
        assertThat(validator.isValid("javascript:alert(1)", null)).isFalse();
        assertThat(validator.isValid("file:///etc/passwd", null)).isFalse();
    }

    @Test
    void rejectsLocalhost() {
        assertThat(validator.isValid("http://localhost/foo", null)).isFalse();
        assertThat(validator.isValid("http://foo.localhost/", null)).isFalse();
    }

    @Test
    void rejectsLoopbackIp() {
        assertThat(validator.isValid("http://127.0.0.1/", null)).isFalse();
        assertThat(validator.isValid("http://[::1]/", null)).isFalse();
    }

    @Test
    void rejectsPrivateRanges() {
        assertThat(validator.isValid("http://10.0.0.1/", null)).isFalse();
        assertThat(validator.isValid("http://192.168.1.1/", null)).isFalse();
    }

    @Test
    void rejectsLinkLocal() {
        assertThat(validator.isValid("http://169.254.169.254/latest/meta-data/", null)).isFalse();
    }

    @Test
    void rejectsMalformed() {
        assertThat(validator.isValid("not-a-url", null)).isFalse();
        assertThat(validator.isValid("https://", null)).isFalse();
    }
}
