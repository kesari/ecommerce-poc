package com.poc.basket.infrastructure.persistence.row;

public record CouponRow(String code, int discountPercent, boolean active) {
}
