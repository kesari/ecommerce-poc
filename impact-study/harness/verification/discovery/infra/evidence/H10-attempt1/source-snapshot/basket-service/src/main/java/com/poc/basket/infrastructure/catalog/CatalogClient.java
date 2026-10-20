package com.poc.basket.infrastructure.catalog;

import com.poc.basket.application.DownstreamUnavailableException;
import com.poc.basket.application.port.CatalogPort;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerRegistry;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryRegistry;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.util.UUID;
import java.util.function.Supplier;

@Component
@EnableConfigurationProperties(CatalogClientProperties.class)
public class CatalogClient implements CatalogPort {

    private final RestClient restClient;
    private final CircuitBreaker circuitBreaker;
    private final Retry retry;

    public CatalogClient(CatalogClientProperties properties,
                         CircuitBreakerRegistry breakerRegistry,
                         RetryRegistry retryRegistry) {
        HttpClient httpClient = HttpClient.newBuilder()
                .connectTimeout(properties.connectTimeout())
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(properties.readTimeout());
        this.restClient = RestClient.builder()
                .baseUrl(properties.baseUrl())
                .requestFactory(requestFactory)
                .build();
        this.circuitBreaker = breakerRegistry.circuitBreaker("catalog");
        this.retry = retryRegistry.retry("catalog");
    }

    @Override
    public ProductInfo lookup(UUID productId) {
        Supplier<ProductInfo> call = () -> fetch(productId);
        Supplier<ProductInfo> guarded = CircuitBreaker.decorateSupplier(circuitBreaker,
                Retry.decorateSupplier(retry, call));
        try {
            return guarded.get();
        } catch (Exception e) {
            throw new DownstreamUnavailableException("catalog unavailable", e);
        }
    }

    private ProductInfo fetch(UUID productId) {
        CatalogProductResponse response;
        try {
            response = restClient.get()
                    .uri("/api/v1/products/{productId}", productId)
                    .retrieve()
                    .body(CatalogProductResponse.class);
        } catch (HttpClientErrorException.NotFound e) {
            return null;
        }
        if (response == null) {
            return null;
        }
        return new ProductInfo(response.id(), response.name(), response.priceMinor(),
                response.currency(), response.active());
    }
}
