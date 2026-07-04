package com.bvisionry.config;

import lombok.extern.slf4j.Slf4j;
import org.springframework.cache.annotation.CachingConfigurer;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.cache.interceptor.CacheErrorHandler;
import org.springframework.cache.interceptor.SimpleCacheErrorHandler;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;
import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

@Slf4j
@Configuration
@EnableCaching
public class CacheConfig implements CachingConfigurer {

    public static final String DASHBOARD_OVERVIEW = "dashboardOverview";
    public static final String DASHBOARD_DISTRIBUTION = "dashboardDistribution";
    public static final String DASHBOARD_COMPLETION = "dashboardCompletion";
    public static final String MEMBER_RESULTS = "memberResults";
    public static final String MEMBER_HISTORY = "memberHistory";
    public static final String PLATFORM_ANALYTICS = "platformAnalytics";
    public static final String DASHBOARD = "dashboard";
    public static final String PLATFORM_SETTINGS = "platformSettings";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory) {
        // Restrict polymorphic deserialization to types we actually cache: project
        // DTOs/records under com.bvisionry.* and the small set of stdlib value
        // types those DTOs nest (collections, primitives' boxed forms, time/UUID/
        // BigDecimal). Replaces the previous "trust everything" config which
        // accepted any Object subtype — a Jackson default-typing gadget surface.
        GenericJacksonJsonRedisSerializer jsonSerializer = GenericJacksonJsonRedisSerializer.builder()
                .enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
                        .allowIfSubType("com.bvisionry.")
                        .allowIfSubType("java.util.")     // List, Map, Set, Optional, UUID, Collections$*
                        .allowIfSubType("java.lang.")     // String, Integer, Long, Boolean, Number, etc.
                        .allowIfSubType("java.math.")     // BigDecimal, BigInteger
                        .allowIfSubType("java.time.")     // Instant, LocalDate, Duration, ...
                        .allowIfSubTypeIsArray()
                        .build())
                .build();

        RedisCacheConfiguration defaultConfig = RedisCacheConfiguration.defaultCacheConfig()
                .serializeKeysWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(new StringRedisSerializer()))
                .serializeValuesWith(
                        RedisSerializationContext.SerializationPair.fromSerializer(jsonSerializer))
                .disableCachingNullValues()
                .entryTtl(Duration.ofMinutes(5));

        Map<String, RedisCacheConfiguration> cacheConfigurations = new HashMap<>();

        cacheConfigurations.put(DASHBOARD_OVERVIEW, defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put(DASHBOARD_DISTRIBUTION, defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put(DASHBOARD_COMPLETION, defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put(MEMBER_RESULTS, defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigurations.put(MEMBER_HISTORY, defaultConfig.entryTtl(Duration.ofMinutes(10)));
        cacheConfigurations.put(PLATFORM_ANALYTICS, defaultConfig.entryTtl(Duration.ofMinutes(15)));
        cacheConfigurations.put(DASHBOARD, defaultConfig.entryTtl(Duration.ofMinutes(5)));
        cacheConfigurations.put(PLATFORM_SETTINGS, defaultConfig.entryTtl(Duration.ofMinutes(1)));

        return RedisCacheManager.builder(connectionFactory)
                .cacheDefaults(defaultConfig)
                .withInitialCacheConfigurations(cacheConfigurations)
                .build();
    }

    @Override
    public CacheErrorHandler errorHandler() {
        return new SimpleCacheErrorHandler() {
            @Override
            public void handleCacheGetError(RuntimeException exception, org.springframework.cache.Cache cache, Object key) {
                log.warn("Redis cache get failed for cache '{}': {}", cache.getName(), exception.getMessage());
            }

            @Override
            public void handleCachePutError(RuntimeException exception, org.springframework.cache.Cache cache, Object key, Object value) {
                log.warn("Redis cache put failed for cache '{}': {}", cache.getName(), exception.getMessage());
            }

            @Override
            public void handleCacheEvictError(RuntimeException exception, org.springframework.cache.Cache cache, Object key) {
                log.warn("Redis cache evict failed for cache '{}': {}", cache.getName(), exception.getMessage());
            }

            @Override
            public void handleCacheClearError(RuntimeException exception, org.springframework.cache.Cache cache) {
                log.warn("Redis cache clear failed for cache '{}': {}", cache.getName(), exception.getMessage());
            }
        };
    }
}
