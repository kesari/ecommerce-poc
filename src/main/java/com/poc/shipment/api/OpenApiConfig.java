package com.poc.shipment.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Shipment Service",
        version = "1.0.0",
        description = "Delivery estimates and shipment records. "
                + "Request and response shapes are frozen in "
                + "cross-repo-impact-study/docs/tasks/wave1-shared-contracts.md."))
public class OpenApiConfig {
}
