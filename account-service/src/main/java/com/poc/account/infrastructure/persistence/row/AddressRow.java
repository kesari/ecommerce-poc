package com.poc.account.infrastructure.persistence.row;

import java.time.Instant;
import java.util.UUID;

public record AddressRow(
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
}
