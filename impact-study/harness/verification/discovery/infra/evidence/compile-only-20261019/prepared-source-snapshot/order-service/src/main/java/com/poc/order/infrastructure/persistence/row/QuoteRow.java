package com.poc.order.infrastructure.persistence.row;

import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

public record QuoteRow(UUID quoteId, UUID userId, long basketVersion, String addressSnapshot,
                       long subtotalMinor, long discountMinor, long shippingMinor, long taxMinor,
                       long totalMinor, String currency, LocalDate promisedFrom,
                       LocalDate promisedTo, Instant expiresAt) {}
