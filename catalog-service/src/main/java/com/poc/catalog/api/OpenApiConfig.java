package com.poc.catalog.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Catalog Service",
        version = "1.0.0",
        description = "Product identity, descriptions, prices, and active status."))
public class OpenApiConfig {
}
