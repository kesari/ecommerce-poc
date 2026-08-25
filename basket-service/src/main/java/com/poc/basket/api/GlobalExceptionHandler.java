package com.poc.basket.api;

import com.poc.basket.application.DownstreamUnavailableException;
import com.poc.basket.domain.exception.BasketVersionConflictException;
import com.poc.basket.domain.exception.CouponAlreadyAppliedException;
import com.poc.basket.domain.exception.CouponInvalidException;
import com.poc.basket.domain.exception.ProductInactiveException;
import com.poc.basket.domain.exception.ProductNotFoundException;
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
    private static final String RETRY_AFTER_SECONDS = "5";

    @ExceptionHandler(ProductNotFoundException.class)
    ProblemDetail productNotFound(ProductNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "product-not-found",
                "Product not found", "PRODUCT_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(ProductInactiveException.class)
    ProblemDetail productInactive(ProductInactiveException e) {
        return problem(HttpStatus.CONFLICT, "product-inactive",
                "Product inactive", "PRODUCT_INACTIVE", e.getMessage());
    }

    @ExceptionHandler(CouponInvalidException.class)
    ProblemDetail couponInvalid(CouponInvalidException e) {
        return problem(HttpStatus.BAD_REQUEST, "coupon-invalid",
                "Coupon invalid", "COUPON_INVALID", e.getMessage());
    }

    @ExceptionHandler(CouponAlreadyAppliedException.class)
    ProblemDetail couponAlreadyApplied(CouponAlreadyAppliedException e) {
        return problem(HttpStatus.CONFLICT, "coupon-already-applied",
                "Coupon already applied", "COUPON_ALREADY_APPLIED", e.getMessage());
    }

    @ExceptionHandler(BasketVersionConflictException.class)
    ProblemDetail versionConflict(BasketVersionConflictException e) {
        return problem(HttpStatus.CONFLICT, "basket-version-conflict",
                "Basket version conflict", "BASKET_VERSION_CONFLICT", e.getMessage());
    }

    @ExceptionHandler(DownstreamUnavailableException.class)
    ResponseEntity<ProblemDetail> downstreamUnavailable(DownstreamUnavailableException e) {
        log.warn("downstream unavailable: {}", e.getMessage());
        return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                .header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS)
                .body(problem(HttpStatus.SERVICE_UNAVAILABLE, "downstream-service-unavailable",
                        "Downstream service unavailable", "DOWNSTREAM_SERVICE_UNAVAILABLE",
                        "A downstream service is unavailable. Retry shortly."));
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
