package com.poc.order.infrastructure.client;

import com.poc.order.application.port.AccountPort;
import com.poc.order.domain.model.AddressSnapshot;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import java.util.UUID;

@Component
public class AccountClient implements AccountPort {

    private final RestClient client;

    public AccountClient(RestClient.Builder builder,
                         @Value("${account.base-url:http://localhost:8081}") String baseUrl) {
        this.client = builder.baseUrl(baseUrl).build();
    }

    record AddressResponse(UUID addressId, String fullName, String line1, String line2,
                           String city, String state, String postalCode, String country,
                           String phoneNumber) {}

    @Override
    @CircuitBreaker(name = "account")
    public AddressSnapshot address(String bearerToken, UUID addressId) {
        try {
            AddressResponse response = client.get()
                    .uri("/api/v1/addresses/{addressId}", addressId)
                    .header(HttpHeaders.AUTHORIZATION, bearerToken)
                    .retrieve()
                    .body(AddressResponse.class);
            if (response == null) {
                throw new DownstreamUnavailableException("account returned an empty body", null);
            }
            return new AddressSnapshot(response.fullName(), response.line1(), response.line2(),
                    response.city(), response.state(), response.postalCode(), response.country());
        } catch (RestClientException e) {
            throw new DownstreamUnavailableException("account-service is unavailable", e);
        }
    }
}
