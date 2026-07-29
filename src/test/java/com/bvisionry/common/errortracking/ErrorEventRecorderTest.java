package com.bvisionry.common.errortracking;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ErrorEventRecorderTest {

    /** The recorder saves off-thread, so every verification is a timeout verification. */
    private static final int ASYNC_TIMEOUT_MS = 5_000;

    private final ErrorEventRepository repository = mock(ErrorEventRepository.class);
    private final ErrorEventRecorder recorder = new ErrorEventRecorder(repository);

    @Test
    void recordBackendException_capturesTypeMessageStackAndRequest() {
        var request = new MockHttpServletRequest("POST", "/api/v1/things");

        recorder.recordBackendException(new IllegalStateException("boom"), request);

        ErrorEvent saved = captureSaved();
        assertThat(saved.getSource()).isEqualTo(ErrorSource.BACKEND);
        assertThat(saved.getExceptionType()).isEqualTo("java.lang.IllegalStateException");
        assertThat(saved.getMessage()).isEqualTo("boom");
        assertThat(saved.getStackTrace()).contains("java.lang.IllegalStateException: boom");
        assertThat(saved.getRequestPath()).isEqualTo("/api/v1/things");
        assertThat(saved.getRequestMethod()).isEqualTo("POST");
    }

    /**
     * A crash loop must not be able to write unbounded rows. EVERY field is capped —
     * requestPath included, which lands in an unbounded TEXT column.
     */
    @Test
    void record_truncatesEveryOversizedField() {
        recorder.record(ErrorSource.WEB, "T".repeat(400), "m".repeat(10_000), "s".repeat(50_000),
                "/" + "p".repeat(9_000), "GETGETGETGETGETGET", "r".repeat(200), "d".repeat(200));

        ErrorEvent saved = captureSaved();
        assertThat(saved.getExceptionType()).hasSize(255);
        assertThat(saved.getMessage()).hasSize(4_000);
        assertThat(saved.getStackTrace()).hasSize(20_000);
        assertThat(saved.getRequestPath()).hasSize(2_000);
        assertThat(saved.getRequestMethod()).hasSize(16);
        assertThat(saved.getRequestId()).hasSize(64);
        assertThat(saved.getDigest()).hasSize(64);
    }

    /** digest must never leak into requestId — they are different namespaces (V159). */
    @Test
    void record_keepsDigestOutOfTheJoinKey() {
        recorder.record(ErrorSource.WEB, "Error", "m", null, "/app", null, null, "abc123digest");

        ErrorEvent saved = captureSaved();
        assertThat(saved.getDigest()).isEqualTo("abc123digest");
        assertThat(saved.getRequestId()).isNull();
    }

    /**
     * Recording runs while an error is already being handled, so a store outage must
     * never surface as a second failure on top of the first — and must never block
     * the caller, which is why the save is off-thread at all.
     */
    @Test
    void record_swallowsStoreFailuresAndNeverThrowsAtTheCaller() {
        when(repository.save(any())).thenThrow(new RuntimeException("db is down"));

        assertThatCode(() -> recorder.recordBackendException(
                new IllegalStateException("boom"), new MockHttpServletRequest()))
                .doesNotThrowAnyException();

        verify(repository, timeout(ASYNC_TIMEOUT_MS)).save(any());
    }

    private ErrorEvent captureSaved() {
        var captor = ArgumentCaptor.forClass(ErrorEvent.class);
        verify(repository, timeout(ASYNC_TIMEOUT_MS)).save(captor.capture());
        return captor.getValue();
    }
}
