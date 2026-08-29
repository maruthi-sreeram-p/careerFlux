package com.careerflux.config;

import java.time.Duration;
import java.util.List;

import com.github.benmanes.caffeine.cache.Caffeine;

import org.springframework.cache.CacheManager;
import org.springframework.cache.caffeine.CaffeineCacheManager;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Caching is deliberately in-process. CareerFlux runs as a single application
 * today, so an external cache would add an operational dependency without
 * buying anything. The abstraction is Spring's {@link CacheManager}, so moving
 * to Redis later is a configuration change rather than a code change.
 */
@Configuration
public class CacheConfig {

    public static final String SKILL_DICTIONARY = "skillDictionary";
    public static final String ROBOTS_TXT = "robotsTxt";
    public static final String SOURCE_STATS = "sourceStats";

    @Bean
    public CacheManager cacheManager() {
        CaffeineCacheManager manager = new CaffeineCacheManager();
        manager.setCaffeine(Caffeine.newBuilder()
                .maximumSize(5_000)
                .expireAfterWrite(Duration.ofMinutes(30)));
        manager.setCacheNames(List.of(SKILL_DICTIONARY, ROBOTS_TXT, SOURCE_STATS));
        return manager;
    }
}
