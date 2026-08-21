package com.bvisionry.common.errortracking;

import com.bvisionry.common.web.RequestCorrelationFilter;
import jakarta.annotation.PreDestroy;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;

import java.io.PrintWriter;
import java.io.StringWriter;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;

/**
 * The single write path into {@link ErrorEvent} — used by
 * {@code GlobalExceptionHandler} for backend 500s and by
 * {@link ErrorEventController} for reports forwarded from the web tier.
 *
 * <p><b>Never on the request thread.</b> The save is handed to a single-threaded,
 * bounded executor. When the DB is the thing that is down — exactly when error
 * volume spikes — a synchronous {@code save} blocks for the full Hikari
 * connection-timeout, so recording would pin every Tomcat worker for seconds each
 * and amplify one outage into an availability incident. Offloading means the
 * request thread returns immediately and a store outage costs us only error rows.
 *
 * <p><b>Bounded and lossy on purpose.</b> One worker, a 100-deep queue and
 * {@link ThreadPoolExecutor.DiscardPolicy}: under a crash loop the queue fills and
 * further reports are dropped rather than growing the heap. Telemetry is exactly
 * the workload where dropping beats blocking.
 *
 * <p>No {@code @Transactional}: a single {@code save} is already transactional
 * inside Spring Data, and the caller's transaction has been rolled back by the time
 * an {@code @RestControllerAdvice} runs — a bare save cleanly starts its own.
 */
@Service
@RequiredArgsConstructor
@Slf4j
public class ErrorEventRecorder {

    /** Bounds one row. Long enough for a real stack; short enough that a crash loop can't fill the disk. */
    private static final int MAX_MESSAGE = 4_000;
    private static final int MAX_STACK_TRACE = 20_000;
    private static final int MAX_TYPE = 255;
    private static final int MAX_PATH = 2_000;
    private static final int MAX_REQUEST_ID = 64;
    private static final int MAX_DIGEST = 64;
    private static final int MAX_METHOD = 16;

    /** Bounded + discarding: see the class javadoc. Not a Spring bean — nothing else may submit to it. */
    private final ExecutorService writer = new ThreadPoolExecutor(
            1, 1, 0L, TimeUnit.MILLISECONDS,
            new ArrayBlockingQueue<>(100),
            runnable -> {
                Thread thread = new Thread(runnable, "error-event-writer");
                thread.setDaemon(true);
                return thread;
            },
            new ThreadPoolExecutor.DiscardPolicy());

    private final ErrorEventRepository errorEventRepository;

    /**
     * Records a backend 500. Reads the correlation id from the MDC on the CALLING
     * thread — the write runs on the executor, which has no MDC of its own.
     */
    public void recordBackendException(Throwable ex, HttpServletRequest request) {
        record(ErrorSource.BACKEND,
                ex.getClass().getName(),
                ex.getMessage(),
                stackTraceOf(ex),
                request == null ? null : request.getRequestURI(),
                request == null ? null : request.getMethod(),
                MDC.get(RequestCorrelationFilter.MDC_KEY),
                null);
    }

    public void record(ErrorSource source,
                       String exceptionType,
                       String message,
                       String stackTrace,
                       String requestPath,
                       String requestMethod,
                       String requestId,
                       String digest) {
        // Build (and truncate) on the caller's thread so the queued task holds an
        // already-bounded object. EVERY field is capped here: the DTO's @Size covers
        // well-behaved clients, this covers everyone else and the backend path too.
        ErrorEvent event = new ErrorEvent();
        event.setSource(source);
        event.setExceptionType(truncate(blankToDefault(exceptionType, "UnknownError"), MAX_TYPE));
        event.setMessage(truncate(message, MAX_MESSAGE));
        event.setStackTrace(truncate(stackTrace, MAX_STACK_TRACE));
        event.setRequestPath(truncate(requestPath, MAX_PATH));
        event.setRequestMethod(truncate(requestMethod, MAX_METHOD));
        event.setRequestId(truncate(requestId, MAX_REQUEST_ID));
        event.setDigest(truncate(digest, MAX_DIGEST));

        writer.execute(() -> {
            try {
                errorEventRepository.save(event);
            } catch (Exception recordingFailure) {
                // Deliberately swallowed: this runs off the request thread, so there is
                // nobody left to tell. WARN, not ERROR, so a recording outage cannot
                // itself look like the incident.
                log.warn("Failed to record error event ({}): {}", source, recordingFailure.toString());
            }
        });
    }

    @PreDestroy
    void shutdown() {
        writer.shutdown();
    }

    private static String stackTraceOf(Throwable ex) {
        StringWriter stackWriter = new StringWriter();
        ex.printStackTrace(new PrintWriter(stackWriter));
        return stackWriter.toString();
    }

    private static String blankToDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private static String truncate(String value, int max) {
        if (value == null || value.length() <= max) return value;
        return value.substring(0, max);
    }
}
