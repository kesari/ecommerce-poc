package com.poc.order.infrastructure.client;

import com.poc.order.application.port.BasketPort;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.List;
import java.util.UUID;

@Component
public class BasketClient implements BasketPort {

    private final RestClient client;

    public BasketClient(RestClient.Builder builder,
                        @Value("${basket.base-url:http://localhost:8083}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    record BasketResponse(UUID basketId, long basketVersion, String couponCode,
                          List<ItemResponse> items, long subtotalMinor, long discountMinor,
                          long totalMinor, String currency) {}

    record ItemResponse(UUID productId, String name, long unitPriceMinor, String currency,
                        int quantity, long lineTotalMinor) {}

    @Override
    @CircuitBreaker(name = "basket")
    public BasketSnapshot currentBasket(String bearerToken) {
        try {
            BasketResponse response = client.get()
                    .uri("/api/v1/basket")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(BasketResponse.class);
            if (response == null) {
                throw new DownstreamUnavailableException("basket returned an empty body", null);
            }
            return new BasketSnapshot(response.basketVersion(), response.couponCode(),
                    response.subtotalMinor(), response.discountMinor(), response.currency(),
                    response.items().stream()
                            .map(item -> new BasketSnapshot.Line(item.productId(), item.name(),
                                    item.unitPriceMinor(), item.quantity()))
                            .toList());
        } catch (RestClientException e) {
            throw new DownstreamUnavailableException("basket-service is unavailable", e);
        }
    }
}
