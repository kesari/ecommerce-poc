package com.poc.bff.api;

import org.slf4j.MDC;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

final class ProxySupport {

    private ProxySupport() {
    }

    static String correlationId() {
        return MDC.get(CorrelationIdFilter.MDC_KEY);
    }

    static <T> ResponseEntity<T> json(ResponseEntity<T> downstream) {
        return ResponseEntity.status(downstream.getStatusCode())
                .contentType(contentTypeOf(downstream))
                .body(downstream.getBody());
    }

    private static MediaType contentTypeOf(ResponseEntity<?> downstream) {
        MediaType type = downstream.getHeaders().getContentType();
        if (type != null) {
            return type;
        }
        return downstream.getStatusCode().isError()
                ? MediaType.APPLICATION_PROBLEM_JSON
                : MediaType.APPLICATION_JSON;
    }

    static String bearer(HttpHeaders headers) {
        return headers.getFirst(HttpHeaders.AUTHORIZATION);
    }
}
