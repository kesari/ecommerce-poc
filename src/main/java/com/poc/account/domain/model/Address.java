package com.poc.account.domain.model;

import java.time.Instant;
import java.util.UUID;

public record Address(
        UUID id,
        UUID userId,
        String fullName,
        String line1,
        String line2,
        String city,
        String state,
        String postalCode,
        String country,
        String phoneNumber,
        Instant createdAt,
        Instant updatedAt
) {

    public Address withDetails(String fullName, String line1, String line2, String city,
                               String state, String postalCode, String country, String phoneNumber) {
        return new Address(id, userId, fullName, line1, line2, city, state,
                postalCode, country, phoneNumber, createdAt, updatedAt);
    }
}
