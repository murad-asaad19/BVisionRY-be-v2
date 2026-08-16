package com.bvisionry.config;

import com.bvisionry.aiconfig.service.RateLimitService;
import com.bvisionry.common.exception.RateLimitExceededException;
import com.bvisionry.common.web.ClientIpResolver;
import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * Per-IP ceiling on the anonymous error-report ingest, including the
 * encoded-path spellings that must not slip past it: the filter matches the
 * DECODED path (what MVC routes on), so {@code /api/v1/error-%65vents} and a
 * matrix-parameter suffix are still counted.
 */
@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class ErrorEventRateLimitFilterTest {

    @Mock private RateLimitService rateLimitService;
    @Mock private ClientIpResolver clientIpResolver;
    @Mock private FilterChain chain;

    private ErrorEventRateLimitFilter filter;
    private MockHttpServletResponse response;

    @BeforeEach
    void setUp() {
        filter = new ErrorEventRateLimitFilter(rateLimitService, clientIpResolver);
        response = new MockHttpServletResponse();
        when(clientIpResolver.resolve(any())).thenReturn("203.0.113.7");
    }

    private MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "/api/v1/error-events",
            "/api/v1/error-%65vents",
            "/api/v1/error-events;a=b",
    })
    void aPostIsCountedRegardlessOfPathSpelling(String uri) throws Exception {
        filter.doFilter(request("POST", uri), response, chain);

        verify(rateLimitService).checkErrorReportLimit("ip:203.0.113.7");
        verify(chain).doFilter(any(), any());
    }

    @Test
    void theSuperAdminReadIsNotCounted() throws Exception {
        filter.doFilter(request("GET", "/api/v1/error-events"), response, chain);

        verifyNoInteractions(rateLimitService);
        verify(chain).doFilter(any(), any());
    }

    @Test
    void anExhaustedBudgetIsRefusedBeforeTheBodyIsRead() throws Exception {
        doThrow(new RateLimitExceededException("Too many error reports"))
                .when(rateLimitService).checkErrorReportLimit(any());

        filter.doFilter(request("POST", "/api/v1/error-events"), response, chain);

        assertThat(response.getStatus()).isEqualTo(429);
        assertThat(response.getContentType()).isEqualTo("application/problem+json");
        verify(chain, never()).doFilter(any(), any());
    }
}
