package com.bvisionry.common.web;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.regex.Pattern;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

/**
 * Unit test for {@link RequestCorrelationFilter}: valid ids are reused + echoed,
 * hostile/absent ids are replaced with a safe generated one, and the MDC key is
 * populated for the duration of the chain but cleared afterwards.
 */
class RequestCorrelationFilterTest {

    private static final Pattern SAFE_REQUEST_ID = Pattern.compile("[A-Za-z0-9._-]{1,64}");

    private RequestCorrelationFilter filter;

    @BeforeEach
    void setUp() {
        MDC.clear();
        filter = new RequestCorrelationFilter();
    }

    @AfterEach
    void tearDown() {
        MDC.clear();
    }

    @Test
    void validIncomingId_isReusedAndEchoed() throws Exception {
        String incoming = "abc-123_DEF.456";
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        req.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, incoming);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter.doFilter(req, res, chain);

        // Reused on the response header and visible in the MDC while the chain ran.
        assertThat(res.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER)).isEqualTo(incoming);
        assertThat(chain.mdcDuringChain).isEqualTo(incoming);
        assertThat(chain.invocations).isEqualTo(1);
    }

    @Test
    void hostileIncomingId_withNewline_isReplaced() throws Exception {
        // A CRLF payload is the classic log-injection attempt: it must never reach the
        // logs verbatim, so it is discarded and replaced with a safe generated id.
        String hostile = "evil\r\nInjected-Header: pwned";
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        req.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, hostile);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter.doFilter(req, res, chain);

        String echoed = res.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertThat(echoed).isNotEqualTo(hostile);
        assertThat(echoed).doesNotContain("\r", "\n");
        assertThat(echoed).matches(SAFE_REQUEST_ID);
        assertThat(chain.mdcDuringChain).isEqualTo(echoed);
    }

    @Test
    void overlongIncomingId_isReplaced() throws Exception {
        String tooLong = "a".repeat(65);
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        req.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, tooLong);
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter.doFilter(req, res, chain);

        String echoed = res.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertThat(echoed).isNotEqualTo(tooLong);
        assertThat(echoed).matches(SAFE_REQUEST_ID);
    }

    @Test
    void absentIncomingId_generatesOne() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        CapturingChain chain = new CapturingChain();

        filter.doFilter(req, res, chain);

        String generated = res.getHeader(RequestCorrelationFilter.REQUEST_ID_HEADER);
        assertThat(generated).isNotNull();
        assertThat(generated).matches(SAFE_REQUEST_ID);
        assertThat(chain.mdcDuringChain).isEqualTo(generated);
    }

    @Test
    void mdcKey_isClearedAfterChain() throws Exception {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        req.addHeader(RequestCorrelationFilter.REQUEST_ID_HEADER, "keep-me");
        MockHttpServletResponse res = new MockHttpServletResponse();

        filter.doFilter(req, res, mock(FilterChain.class));

        // No leak into the pooled thread's MDC once the request is done.
        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    @Test
    void mdcKey_isClearedEvenWhenChainThrows() {
        MockHttpServletRequest req = new MockHttpServletRequest("GET", "/api/anything");
        MockHttpServletResponse res = new MockHttpServletResponse();
        FilterChain throwing = (request, response) -> {
            throw new RuntimeException("boom");
        };

        try {
            filter.doFilter(req, res, throwing);
        } catch (Exception ignored) {
            // The filter must still clear the MDC via its finally block.
        }

        assertThat(MDC.get(RequestCorrelationFilter.MDC_KEY)).isNull();
    }

    /**
     * Records the MDC correlation value at the moment the chain is invoked (i.e. while
     * the "downstream" is executing) so tests can assert the id is live mid-request.
     */
    private static final class CapturingChain implements FilterChain {
        private String mdcDuringChain;
        private int invocations;

        @Override
        public void doFilter(jakarta.servlet.ServletRequest request,
                             jakarta.servlet.ServletResponse response) {
            this.mdcDuringChain = MDC.get(RequestCorrelationFilter.MDC_KEY);
            this.invocations++;
        }
    }
}
