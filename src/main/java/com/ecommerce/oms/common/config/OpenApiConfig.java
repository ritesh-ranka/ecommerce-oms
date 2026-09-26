package com.ecommerce.oms.common.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.info.License;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * Swagger UI doubles as the manual test client for this project, so the bearer scheme is
 * registered globally — "Authorize", paste the token from {@code /auth/login}, and every
 * protected endpoint becomes callable from the browser.
 */
@Configuration
public class OpenApiConfig {

    private static final String BEARER_SCHEME = "bearerAuth";

    @Bean
    public OpenAPI omsOpenApi() {
        return new OpenAPI()
                .info(new Info()
                        .title("E-commerce Order Management API")
                        .version("1.0.0")
                        .description("""
                                Multi-warehouse order management: catalog, cart, checkout with
                                reservation-based inventory, payments, fulfillment lifecycle,
                                returns and refunds.

                                **Getting started**
                                1. `POST /api/v1/auth/login` with `customer@oms.dev` / `Password@123`
                                2. Click **Authorize** and paste the `accessToken`
                                3. `POST /api/v1/cart/items`, then `POST /api/v1/cart/preview`,
                                   then `POST /api/v1/checkout` with an `Idempotency-Key` header

                                Roles: `ADMIN`, `CUSTOMER`, `WAREHOUSE_STAFF`.
                                Every response carries an `X-Trace-Id` header that appears in the logs.
                                """)
                        .contact(new Contact().name("Order Management Service"))
                        .license(new License().name("MIT")))
                .servers(List.of(new Server().url("http://localhost:8080").description("Local")))
                .components(new Components().addSecuritySchemes(BEARER_SCHEME,
                        new SecurityScheme()
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("Paste the accessToken returned by /api/v1/auth/login")))
                .addSecurityItem(new SecurityRequirement().addList(BEARER_SCHEME));
    }
}
