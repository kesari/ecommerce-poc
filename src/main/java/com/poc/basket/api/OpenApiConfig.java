package com.poc.basket.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Basket Service",
        version = "1.0.0",
        description = "Active baskets, basket items, coupon application, and pricing totals."))
public class OpenApiConfig {
}
