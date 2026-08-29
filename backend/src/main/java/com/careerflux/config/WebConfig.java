package com.careerflux.config;

import java.time.Duration;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.client.SimpleClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

@Configuration
public class WebConfig {

    /**
     * The single outbound HTTP client used by source adapters, robots.txt checks
     * and health probes. It always identifies itself: CareerFlux only ever talks
     * to endpoints that permit automated access, and a site operator must be able
     * to see who is calling and contact us.
     */
    @Bean
    public RestClient sourceRestClient(CareerFluxProperties properties) {
        Duration timeout = properties.sources().requestTimeout();
        SimpleClientHttpRequestFactory factory = new SimpleClientHttpRequestFactory();
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", properties.sources().userAgent())
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .build();
    }

    @Bean
    public RestClientCustomizer defaultRestClientCustomizer(CareerFluxProperties properties) {
        return builder -> builder.defaultHeader("User-Agent", properties.sources().userAgent());
    }
}
