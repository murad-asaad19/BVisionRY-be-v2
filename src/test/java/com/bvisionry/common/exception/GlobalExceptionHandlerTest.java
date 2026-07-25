package com.bvisionry.common.exception;

import com.bvisionry.common.errortracking.ErrorEventRecorder;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.mock.web.MockHttpServletRequest;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

class GlobalExceptionHandlerTest {

    private final ErrorEventRecorder recorder = mock(ErrorEventRecorder.class);
    private final GlobalExceptionHandler handler = new GlobalExceptionHandler(recorder);

    @Test
    void handleResourceNotFound_returns404() {
        var ex = new ResourceNotFoundException("User", "123");
        var problem = handler.handleResourceNotFound(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.NOT_FOUND.value());
        assertThat(problem.getDetail()).contains("User");
    }

    @Test
    void handleBadRequest_returns400() {
        var ex = new BadRequestException("Invalid email");
        var problem = handler.handleBadRequest(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.BAD_REQUEST.value());
        assertThat(problem.getDetail()).isEqualTo("Invalid email");
    }

    /** The 500 catch-all is the ONLY handler that feeds the aggregated error store. */
    @Test
    void handleGeneral_returns500AndRecordsTheException() {
        var ex = new IllegalStateException("boom");
        var request = new MockHttpServletRequest("GET", "/api/v1/whatever");

        var problem = handler.handleGeneral(ex, request);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR.value());
        verify(recorder).recordBackendException(ex, request);
    }

    /** Expected, deliberately-mapped conditions must never pollute the store. */
    @Test
    void mappedExceptions_areNotRecorded() {
        handler.handleResourceNotFound(new ResourceNotFoundException("User", "123"));
        handler.handleBadRequest(new BadRequestException("Invalid email"));

        verifyNoInteractions(recorder);
    }
}
