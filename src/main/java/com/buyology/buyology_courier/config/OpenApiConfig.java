package com.buyology.buyology_courier.config;

import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.info.Contact;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityRequirement;
import io.swagger.v3.oas.models.security.SecurityScheme;
import io.swagger.v3.oas.models.servers.Server;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI buyologyCourierOpenAPI() {
        final String bearerSchemeName = "bearerAuth";

        return new OpenAPI()
                .info(new Info()
                        .title("Buyology Courier API")
                        .version("1.0.0")
                        .description("""
                                REST API for the Buyology courier management service.

                                **Roles**:
                                - `COURIER` — JWT issued by this service after courier login
                                - `ADMIN` / `COURIER_ADMIN` — Keycloak RSA JWT
                                - `ECOMMERCE_SERVICE` — RSA JWT from the ecommerce backend

                                **Key flows**:
                                - Couriers record GPS pings via `POST /api/v1/couriers/{id}/locations`
                                - Admin views all courier locations on a map via `GET /api/v1/couriers/map`
                                - Deliveries are created asynchronously via RabbitMQ from the ecommerce service
                                """)
                        .contact(new Contact()
                                .name("Buyology Team")
                                .email("firdovsirz@gmail.com")))
                .servers(List.of(
                        new Server().url("http://localhost:8081").description("Local development"),
                        new Server().url("https://courier.buyology.com").description("Production")))
                .addSecurityItem(new SecurityRequirement().addList(bearerSchemeName))
                .components(new Components()
                        .addSecuritySchemes(bearerSchemeName, new SecurityScheme()
                                .name(bearerSchemeName)
                                .type(SecurityScheme.Type.HTTP)
                                .scheme("bearer")
                                .bearerFormat("JWT")
                                .description("JWT — courier token from POST /api/v1/auth/signin, or Keycloak token for admin roles")));
    }
}
