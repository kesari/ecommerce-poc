package com.poc.order.api;

import com.poc.order.domain.exception.BasketVersionChangedException;
import com.poc.order.domain.exception.IdempotencyKeyRequiredException;
import com.poc.order.domain.exception.IdempotencyKeyReusedException;
import com.poc.order.domain.exception.OrderNotFoundException;
import com.poc.order.domain.exception.QuoteExpiredException;
import com.poc.order.domain.exception.QuoteNotFoundException;
import com.poc.order.domain.exception.UnsupportedPaymentMethodException;
import com.poc.order.infrastructure.client.DownstreamUnavailableException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://poc.example/problems/";

    @ExceptionHandler(QuoteNotFoundException.class)
    ProblemDetail quoteNotFound(QuoteNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "quote-not-found", "Quote not found",
                "QUOTE_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(OrderNotFoundException.class)
    ProblemDetail orderNotFound(OrderNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "order-not-found", "Order not found",
                "ORDER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(QuoteExpiredException.class)
    ProblemDetail quoteExpired(QuoteExpiredException e) {
        return problem(HttpStatus.GONE, "quote-expired", "Quote expired",
                "QUOTE_EXPIRED", e.getMessage());
    }

    @ExceptionHandler(BasketVersionChangedException.class)
    ProblemDetail basketVersionChanged(BasketVersionChangedException e) {
        return problem(HttpStatus.CONFLICT, "basket-version-changed", "Basket version changed",
                "BASKET_VERSION_CHANGED", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyReusedException.class)
    ProblemDetail idempotencyReused(IdempotencyKeyReusedException e) {
        return problem(HttpStatus.CONFLICT, "idempotency-key-reused", "Idempotency key reused",
                "IDEMPOTENCY_KEY_REUSED", e.getMessage());
    }

    @ExceptionHandler(IdempotencyKeyRequiredException.class)
    ProblemDetail idempotencyRequired(IdempotencyKeyRequiredException e) {
        return problem(HttpStatus.BAD_REQUEST, "idempotency-key-required",
                "Idempotency key required", "IDEMPOTENCY_KEY_REQUIRED", e.getMessage());
    }

    @ExceptionHandler(UnsupportedPaymentMethodException.class)
    ProblemDetail unsupportedPaymentMethod(UnsupportedPaymentMethodException e) {
        return problem(HttpStatus.BAD_REQUEST, "unsupported-payment-method",
                "Unsupported payment method", "UNSUPPORTED_PAYMENT_METHOD", e.getMessage());
    }

    @ExceptionHandler(DownstreamUnavailableException.class)
    ResponseEntity<ProblemDetail> downstreamUnavailable(DownstreamUnavailableException e) {
        log.warn("downstream unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, "5")
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "downstream-service-unavailable",
                        "Downstream service unavailable", "DOWNSTREAM_SERVICE_UNAVAILABLE",
                        "A downstream service is unavailable. Retry shortly."));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ProblemDetail invalidRequest(MethodArgumentNotValidException e) {
        return problem(HttpStatus.BAD_REQUEST, "invalid-request", "Invalid request",
                "INVALID_REQUEST", "Request validation failed.");
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
