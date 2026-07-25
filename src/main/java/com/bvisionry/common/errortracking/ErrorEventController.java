package com.bvisionry.common.errortracking;

import com.bvisionry.common.exception.AuthenticationException;
import io.swagger.v3.oas.annotations.Parameter;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

/**
 * The aggregated error store's two ends: web tier writes, SUPER_ADMIN reads.
 *
 * <p><b>Ingest defence, three independent layers.</b> No single one of them is the
 * boundary:
 * <ol>
 *   <li><b>URL layer</b> — {@code permitAll} + CSRF-exempt, because a crashing
 *       browser has no session and the Next server has no user. Anonymous by
 *       necessity, like {@code /api/v1/contact}.</li>
 *   <li><b>Proxy secret</b> — the request must carry a matching
 *       {@code X-Bvisionry-Proxy-Secret}, i.e. it must have come through our own
 *       BFF hop rather than straight off the internet. Fails CLOSED: an unset or
 *       blank configured secret rejects every report.</li>
 *   <li><b>Rate limit</b> — a dedicated per-real-client-IP bucket in
 *       {@code com.bvisionry.config.ErrorEventRateLimitFilter}, plus
 *       {@code @Size} caps on every field of {@link ReportErrorEventRequest}. This
 *       is what bounds the browser-reachable path, where the secret alone proves
 *       only "came through the front door".</li>
 * </ol>
 *
 * <p>The secret is attached by the BFF catch-all on its own server-side hop, which
 * is exactly its documented meaning. It is deliberately NOT attached by anything
 * the browser can invoke directly — an endpoint that both holds this credential and
 * accepts anonymous input is a confused deputy, which is why the web tier reports
 * through the proxy and never through a Server Action.
 */
@RestController
@RequestMapping("/api/v1/error-events")
public class ErrorEventController {

    private static final String PROXY_SECRET_HEADER = "X-Bvisionry-Proxy-Secret";
    private static final int MAX_PAGE_SIZE = 200;

    private final ErrorEventRecorder recorder;
    private final ErrorEventRepository errorEventRepository;
    private final String proxySharedSecret;

    // Constructor injection, not @Value fields: ArchUnit rule 4 forbids field injection.
    public ErrorEventController(ErrorEventRecorder recorder,
                                ErrorEventRepository errorEventRepository,
                                @Value("${bvisionry.proxy.shared-secret:}") String proxySharedSecret) {
        this.recorder = recorder;
        this.errorEventRepository = errorEventRepository;
        this.proxySharedSecret = proxySharedSecret;
    }

    /**
     * Record an unhandled error from the web tier. Returns 202 and no body — the
     * caller is already in a failure path and has nothing useful to do with a
     * result. Rate limiting runs in the filter, BEFORE body deserialization, so a
     * malformed flood also consumes tokens.
     */
    // Method names are the springdoc operationIds and therefore part of the generated
    // web contract — kept unique so adding this endpoint doesn't renumber the existing
    // `list_N` / `report_N` operations across the whole schema.
    @PostMapping
    @ResponseStatus(HttpStatus.ACCEPTED)
    public void reportErrorEvent(
            // Hidden from the spec: the BFF attaches this on its own hop, so it is
            // transport plumbing, not part of the caller's contract. Marking it a
            // visible optional parameter would be a lie (the endpoint 401s without
            // it) and would leak the header name into the generated web types.
            @Parameter(hidden = true)
            @RequestHeader(name = PROXY_SECRET_HEADER, required = false) String presentedSecret,
            @Valid @RequestBody ReportErrorEventRequest request) {
        requireProxySecret(presentedSecret);
        recorder.record(ErrorSource.WEB,
                request.exceptionType(),
                request.message(),
                request.stackTrace(),
                request.requestPath(),
                request.requestMethod(),
                request.requestId(),
                request.digest());
    }

    /** Browse both tiers, most recent first. Optional {@code source} filter. */
    @GetMapping
    @PreAuthorize("hasAuthority('SUPER_ADMIN')")
    public Page<ErrorEventResponse> listErrorEvents(@RequestParam(required = false) ErrorSource source,
                                                    @RequestParam(defaultValue = "0") int page,
                                                    @RequestParam(defaultValue = "50") int size) {
        var pageable = PageRequest.of(Math.max(page, 0),
                Math.min(Math.max(size, 1), MAX_PAGE_SIZE),
                Sort.by(Sort.Direction.DESC, "createdAt"));
        return errorEventRepository.findFiltered(source, pageable).map(ErrorEventResponse::from);
    }

    /**
     * Fails CLOSED — a blank configured secret rejects everything rather than
     * silently opening an unauthenticated write endpoint. Constant-time compare so
     * the check itself leaks nothing.
     */
    private void requireProxySecret(String presentedSecret) {
        if (proxySharedSecret == null || proxySharedSecret.isBlank()
                || presentedSecret == null
                || !MessageDigest.isEqual(proxySharedSecret.getBytes(StandardCharsets.UTF_8),
                                          presentedSecret.getBytes(StandardCharsets.UTF_8))) {
            throw new AuthenticationException("Invalid or missing proxy secret.");
        }
    }
}
