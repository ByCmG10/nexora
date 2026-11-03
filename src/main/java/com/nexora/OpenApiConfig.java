package com.nexora;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    static final String BASIC_AUTH = "basicAuth";

    @Bean
    OpenAPI nexoraOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("NEXORA API")
                        .version("1.0.0")
                        .description("Inventory REST API: manage products by SKU, track stock and price, and query "
                                + "them with combinable filters, pagination and sorting. "
                                + "/api/products/filter-optional is the general-purpose search; the other query "
                                + "endpoints are kept for compatibility."))
                .components(new Components().addSecuritySchemes(BASIC_AUTH,
                        new SecurityScheme().type(SecurityScheme.Type.HTTP).scheme("basic")))
                .addSecurityItem(new SecurityRequirement().addList(BASIC_AUTH));
    }
}
