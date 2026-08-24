package com.poc.bff.infrastructure.client;

import com.fasterxml.jackson.databind.JsonNode;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;
import java.util.Map;
import java.util.function.Supplier;

@Component
@EnableConfigurationProperties(DownstreamProperties.class)
public class DownstreamRelay {

    public static final String CORRELATION_HEADER = "X-Correlation-Id";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";
    private static final Duration CONNECT_TIMEOUT = Duration.ofMillis(500);
    private static final Duration READ_TIMEOUT = Duration.ofMillis(1500);

    private final Map<String, RestClient> clients;
    private final CircuitBreakerRegistry breakers;

    public DownstreamRelay(DownstreamProperties properties, CircuitBreakerRegistry breakers) {
        RestClient.Builder builder = RestClient.builder().requestFactory(requestFactory());
        this.clients = Map.of(
                "account", builder.clone().baseUrl(properties.account()).build(),
                "catalog", builder.clone().baseUrl(properties.catalog()).build(),
                "basket", builder.clone().baseUrl(properties.basket()).build(),
                "order", builder.clone().baseUrl(properties.order()).build());
        this.breakers = breakers;
    }

    public ResponseEntity<JsonNode> relay(String service, HttpMethod method, String path,
                                          Object body, Map<String, String> headers) {
        Supplier<ResponseEntity<JsonNode>> call = () -> {
            RestClient.RequestBodySpec spec = clients.get(service)
                    .method(method)
                    .uri(path)
                    .accept(MediaType.APPLICATION_JSON, MediaType.APPLICATION_PROBLEM_JSON);
            headers.forEach(spec::header);
            RestClient.RequestHeadersSpec<?> ready = body == null
                    ? spec
                    : spec.contentType(MediaType.APPLICATION_JSON).body(body);
            ResponseEntity<JsonNode> response = ready.retrieve()
                    .onStatus(status -> true, (request, ignored) -> { })
                    .toEntity(JsonNode.class);
            if (response.getStatusCode().is5xxServerError()) {
                throw new DownstreamUnavailableException(service,
                        service + " returned " + response.getStatusCode().value(), null);
            }
            return response;
        };
        try {
            return breakers.circuitBreaker(service).executeSupplier(call);
        } catch (DownstreamUnavailableException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new DownstreamUnavailableException(service,
                    service + "-service is unavailable", e);
        }
    }

    public static Map<String, String> forward(String bearerToken, String correlationId) {
        return headers(bearerToken, correlationId, null);
    }

    public static Map<String, String> headers(String bearerToken, String correlationId,
                                              String idempotencyKey) {
        Map<String, String> headers = new java.util.LinkedHashMap<>();
        if (bearerToken != null && !bearerToken.isBlank()) {
            headers.put(HttpHeaders.AUTHORIZATION, bearerToken);
        }
        if (correlationId != null && !correlationId.isBlank()) {
            headers.put(CORRELATION_HEADER, correlationId);
        }
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            headers.put(IDEMPOTENCY_HEADER, idempotencyKey);
        }
        return headers;
    }

    private static org.springframework.http.client.JdkClientHttpRequestFactory requestFactory() {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(CONNECT_TIMEOUT)
                .version(HttpClient.Version.HTTP_1_1)
                .build();
        var factory = new org.springframework.http.client.JdkClientHttpRequestFactory(httpClient);
        factory.setReadTimeout(READ_TIMEOUT);
        return factory;
    }
}
