package com.careerflux.config;

import java.time.Duration;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

import com.careerflux.source.net.SafeClientHttpRequestFactory;
import com.careerflux.source.net.SafeUrlValidator;

@Configuration
public class WebConfig {

    /**
     * The single outbound HTTP client used by source adapters, robots.txt checks
     * and health probes. It always identifies itself: CareerFlux only ever talks
     * to endpoints that permit automated access, and a site operator must be able
     * to see who is calling and contact us.
     */
    @Bean
    public RestClient sourceRestClient(CareerFluxProperties properties, SafeUrlValidator urlValidator) {
        Duration timeout = properties.sources().requestTimeout();
        // Every request through this client is validated and no redirect is
        // followed unseen. Putting that in the transport rather than in each
        // caller means a new adapter cannot forget it.
        SafeClientHttpRequestFactory factory = new SafeClientHttpRequestFactory(urlValidator);
        factory.setConnectTimeout((int) timeout.toMillis());
        factory.setReadTimeout((int) timeout.toMillis());
        return RestClient.builder()
                .requestFactory(factory)
                .defaultHeader("User-Agent", properties.sources().userAgent())
                .defaultHeader("Accept", "application/json, text/plain, */*")
                .build();
    }

    /**
     * Makes the SSRF guard structural rather than remembered.
     *
     * <p>Spring applies this to every injected {@code RestClient.Builder}, so any
     * component that builds its own client — as the discovery probe does — gets
     * the validating transport whether or not its author thought about it. Before
     * this, protection depended on each site passing
     * {@link SafeClientHttpRequestFactory} by hand: the probe remembered, and the
     * next component to be written might not have. A control that has to be
     * remembered is a control that eventually is not.
     *
     * <p>A builder that sets its own request factory afterwards still overrides
     * this, which is deliberate — {@code AtsBoardProbe} does exactly that to
     * apply shorter discovery timeouts, and it passes a safe factory of its own.
     * What changed is the default: forgetting now yields a protected client
     * rather than an unprotected one.
     */
    @Bean
    public RestClientCustomizer safeOutboundDefaults(CareerFluxProperties properties,
                                                    SafeUrlValidator urlValidator) {
        Duration timeout = properties.sources().requestTimeout();
        return builder -> {
            SafeClientHttpRequestFactory factory = new SafeClientHttpRequestFactory(urlValidator);
            factory.setConnectTimeout((int) timeout.toMillis());
            factory.setReadTimeout((int) timeout.toMillis());
            builder.requestFactory(factory)
                    .defaultHeader("User-Agent", properties.sources().userAgent());
        };
    }
}
