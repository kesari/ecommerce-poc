package com.poc.bff.api;

import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.info.Info;
import org.springframework.context.annotation.Configuration;

@Configuration
@OpenAPIDefinition(info = @Info(
        title = "Commerce BFF",
        version = "1.0.0",
        description = "The only backend the SPA calls. Pass-through to account, catalog, "
                + "basket, and order; owns no business rules. Contract frozen in "
                + "cross-repo-impact-study/docs/tasks/step9-commerce-bff.md."))
public class OpenApiConfig {
}
