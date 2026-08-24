package com.poc.order.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Order Service",
        version = "1.0.0",
        description = "Checkout quotes, idempotent order placement, and saga status. "
                + "Contracts are frozen in "
                + "cross-repo-impact-study/docs/tasks/step8-order-service.md."))
public class OpenApiConfig {
}
