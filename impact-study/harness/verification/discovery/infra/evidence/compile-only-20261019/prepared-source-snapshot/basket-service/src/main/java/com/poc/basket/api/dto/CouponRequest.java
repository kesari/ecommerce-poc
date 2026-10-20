package com.poc.basket.api.dto;

import jakarta.validation.constraints.NotBlank;

public record CouponRequest(@NotBlank String code) {}
