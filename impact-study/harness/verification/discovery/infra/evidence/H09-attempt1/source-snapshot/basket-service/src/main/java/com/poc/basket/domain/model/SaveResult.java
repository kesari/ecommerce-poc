package com.poc.basket.domain.model;

public sealed interface SaveResult {
    record Saved(Basket basket) implements SaveResult {}
    record VersionConflict(long currentVersion) implements SaveResult {}
}
