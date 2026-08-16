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

    /**
     * The house shape for "a well-formed request refused by current account
     * state" — e.g. publishing before a workshop is finished, or (the org
     * storage quota refusal in {@code OrgStorageQuotaService}) an upload that
     * would push the org over its object-storage quota. 409, not 400: the
     * request itself is valid, the account's current state is what refuses it.
     */
    @Test
    void handleIllegalOperation_returns409() {
        var ex = new IllegalOperationException("Organization storage quota exceeded");
        var problem = handler.handleIllegalOperation(ex);

        assertThat(problem.getStatus()).isEqualTo(HttpStatus.CONFLICT.value());
        assertThat(problem.getDetail()).isEqualTo("Organization storage quota exceeded");
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
