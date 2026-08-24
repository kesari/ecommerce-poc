package com.poc.bff.api;

import com.poc.bff.infrastructure.client.DownstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://poc.example/problems/";

    @ExceptionHandler(DownstreamUnavailableException.class)
    ResponseEntity<ProblemDetail> downstreamUnavailable(DownstreamUnavailableException e) {
        log.warn("downstream {} unavailable: {}", e.service(), e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "downstream-service-unavailable",
                        "Downstream service unavailable", "DOWNSTREAM_SERVICE_UNAVAILABLE",
                        "The " + e.service() + " service is unavailable. Retry shortly."));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ProblemDetail invalidRequest(HttpMessageNotReadableException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                "INVALID_REQUEST", "Request body could not be read.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error", "Internal error",
                "INTERNAL_ERROR", "An unexpected error occurred. The incident has been logged.");
    }

    static ProblemDetail problem(HttpStatus status, String typeSegment, String title,
                                 String code, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setType(URI.create(TYPE_BASE + typeSegment));
        problem.setTitle(title);
        problem.setProperty("code", code);
        problem.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return problem;
    }
}
