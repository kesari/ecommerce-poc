package com.poc.shipment.api;

import com.poc.shipment.domain.exception.ShipmentNotFoundException;
import com.poc.shipment.domain.exception.UnsupportedDestinationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://poc.example/problems/";

    @ExceptionHandler(ShipmentNotFoundException.class)
    ProblemDetail shipmentNotFound(ShipmentNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "shipment-not-found",
                "Shipment not found", "SHIPMENT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(UnsupportedDestinationException.class)
    ProblemDetail unsupportedDestination(UnsupportedDestinationException e) {
        return problem(HttpStatus.BAD_REQUEST, "unsupported-destination",
                "Unsupported destination", "UNSUPPORTED_DESTINATION", e.getMessage());
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request",
                "Invalid request", "INVALID_REQUEST", "Request validation failed.");
    }

    @ExceptionHandler(Exception.class)
    ProblemDetail unexpected(Exception e) {
        log.error("unhandled error", e);
        return problem(HttpStatus.INTERNAL_SERVER_ERROR, "internal-error",
                "Internal error", "INTERNAL_ERROR",
                "An unexpected error occurred. The incident has been logged.");
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
