package com.bvisionry.common.exception;

/**
 * Thrown when a server-side report rendering pipeline (PDF / Excel) fails
 * mid-generation — typically because Thymeleaf template processing, font
 * registration, or the PDF/XLSX writer threw. Falls through to the generic
 * 500 handler in {@link GlobalExceptionHandler}, which replaces the message
 * with a safe one so we don't leak internals.
 *
 * <p>Lets log filters and metrics distinguish "report generation broke" from
 * "something else broke" without parsing strings out of the cause chain.
 */
public class ReportGenerationException extends RuntimeException {
    public ReportGenerationException(String message, Throwable cause) {
        super(message, cause);
    }
}
