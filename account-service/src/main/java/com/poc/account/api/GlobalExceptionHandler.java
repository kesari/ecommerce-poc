package com.poc.account.api;

import com.poc.account.domain.exception.AddressNotFoundException;
import com.poc.account.domain.exception.EmailAlreadyRegisteredException;
import com.poc.account.domain.exception.InvalidCredentialsException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

@RestControllerAdvice
public class GlobalExceptionHandler {

    private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);
    private static final String TYPE_BASE = "https://poc.example/problems/";

    @ExceptionHandler(EmailAlreadyRegisteredException.class)
    ProblemDetail emailTaken(EmailAlreadyRegisteredException e) {
        return problem(HttpStatus.CONFLICT, "email-already-registered",
                "Email already registered", "EMAIL_ALREADY_REGISTERED",
                "An account with this email already exists.");
    }

    @ExceptionHandler(InvalidCredentialsException.class)
    ProblemDetail invalidCredentials(InvalidCredentialsException e) {
        return problem(HttpStatus.UNAUTHORIZED, "invalid-credentials",
                "Invalid credentials", "INVALID_CREDENTIALS",
                "Email or password is incorrect.");
    }

    @ExceptionHandler(AddressNotFoundException.class)
    ProblemDetail addressNotFound(AddressNotFoundException e) {
        return problem(HttpStatus.NOT_FOUND, "address-not-found",
                "Address not found", "ADDRESS_NOT_FOUND",
                "The requested address does not exist.");
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
        ProblemDetail detail$ = ProblemDetail.forStatusAndDetail(status, detail);
        detail$.setType(java.net.URI.create(TYPE_BASE + typeSegment));
        detail$.setTitle(title);
        detail$.setProperty("code", code);
        detail$.setProperty("correlationId", MDC.get(CorrelationIdFilter.MDC_KEY));
        return detail$;
    }
}
