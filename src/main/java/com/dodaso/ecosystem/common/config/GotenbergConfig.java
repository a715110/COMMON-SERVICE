package com.dodaso.ecosystem.common.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.reactive.function.client.WebClient;

/**
 * WebClient bean for calling the self-hosted Gotenberg container (free,
 * MIT-licensed, wraps LibreOffice behind a REST API -- see
 * GotenbergClient's Javadoc for the endpoint it calls).
 *
 * Follows the same config convention as the existing
 * dodaso.instance.*-base-url properties: a plain property backed by an
 * env var in application.properties, with a literal localhost default in
 * application-local.properties. Spring's WebClient already pulls in
 * spring-boot-starter-webflux, which this module already depends on for
 * other reasons -- no new Maven dependency needed.
 */
@Configuration
public class GotenbergConfig {

    @Bean
    public WebClient gotenbergWebClient(@Value("${gotenberg.base-url}") final String gotenbergBaseUrl) {
        return WebClient.builder()
                .baseUrl(gotenbergBaseUrl)
                .build();
    }
}
