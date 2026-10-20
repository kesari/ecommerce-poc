package com.poc.catalog.api.dto;

import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;
import java.util.UUID;

public record BatchLookupRequest(@NotEmpty @Size(max = 50) List<UUID> productIds) {
}
