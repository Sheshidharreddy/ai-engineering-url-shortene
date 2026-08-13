package com.sheshidhar.urlshortener.config;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.Components;
import io.swagger.v3.oas.models.info.Info;
import io.swagger.v3.oas.models.security.SecurityScheme;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OpenApiConfig {

    @Bean
    OpenAPI urlShortenerOpenApi() {
        return new OpenAPI()
                .components(new Components().addSecuritySchemes(
                        "managementApiKey",
                        new SecurityScheme()
                                .type(SecurityScheme.Type.APIKEY)
                                .in(SecurityScheme.In.HEADER)
                                .name("X-API-Key")
                ))
                .info(new Info()
                        .title("URL Shortener API")
                        .description("Creates short links and redirects them to validated HTTP(S) destinations.")
                        .version("v1"));
    }
}
