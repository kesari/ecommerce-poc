package com.poc.account.api.dto;

import java.time.Instant;
import java.util.UUID;

public record AddressResponse(
        UUID id,
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
}
