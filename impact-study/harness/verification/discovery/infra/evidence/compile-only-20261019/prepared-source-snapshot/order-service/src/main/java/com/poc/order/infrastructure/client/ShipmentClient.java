package com.poc.order.infrastructure.client;

import com.poc.order.application.port.ShipmentPort;
import com.poc.order.domain.model.DeliveryWindow;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.time.LocalDate;
import java.util.Map;

@Component
public class ShipmentClient implements ShipmentPort {

    private final RestClient client;

    public ShipmentClient(RestClient.Builder builder,
                          @Value("${shipment.base-url:http://localhost:8087}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    record EstimateResponse(LocalDate fromDate, LocalDate toDate, long shippingChargeMinor,
                            String currency) {}

    @Override
    @CircuitBreaker(name = "shipment")
    public DeliveryEstimate estimate(String bearerToken, String postalCode, int itemCount,
                                     long subtotalMinor) {
        try {
            EstimateResponse response = client.post()
                    .uri("/api/v1/delivery-estimates")
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .body(Map.of("postalCode", postalCode, "itemCount", itemCount,
                            "subtotalMinor", subtotalMinor))
                    .retrieve()
                    .body(EstimateResponse.class);
            if (response == null) {
                throw new DownstreamUnavailableException("shipment returned an empty body", null);
            }
            return new DeliveryEstimate(new DeliveryWindow(response.fromDate(), response.toDate()),
                    response.shippingChargeMinor(), response.currency());
        } catch (RestClientException e) {
            throw new DownstreamUnavailableException("shipment-service is unavailable", e);
        }
    }
}
