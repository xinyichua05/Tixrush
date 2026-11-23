package com.tixrush.config;

import com.tixrush.event.EventResponse;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.JacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext.SerializationPair;

import java.time.Duration;

/**
 * Turns on Spring's cache annotations (@Cacheable, @CachePut, @CacheEvict) and stores
 * the cached values in Redis.
 *
 * The connection itself (host, password, TLS) needs no code: Spring Boot builds it
 * from spring.data.redis.url in application.yml.
 */
@Configuration
@EnableCaching
public class RedisConfig {

    public static final String EVENT_CACHE = "event";

    @Bean
    public RedisCacheManager cacheManager(RedisConnectionFactory connectionFactory,
                                          @Value("${tixrush.cache.event-ttl:5s}") Duration eventTtl) {
        RedisCacheConfiguration eventCache = RedisCacheConfiguration.defaultCacheConfig()
                .entryTtl(eventTtl)                 // entries delete themselves after 5s
                .disableCachingNullValues()
                .prefixCacheNameWith("tixrush:cache:") // key looks like tixrush:cache:event::42
                // Store as readable JSON, and read it back as an EventResponse.
                .serializeValuesWith(SerializationPair.fromSerializer(
                        new JacksonJsonRedisSerializer<>(EventResponse.class)));

        return RedisCacheManager.builder(connectionFactory)
                .withCacheConfiguration(EVENT_CACHE, eventCache)
                .build();
    }
}
