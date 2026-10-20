package com.poc.basket.application.port;

import java.util.UUID;

public interface CatalogPort {

    ProductInfo lookup(UUID productId);

    record ProductInfo(UUID id, String name, long priceMinor, String currency, boolean active) {}
}
