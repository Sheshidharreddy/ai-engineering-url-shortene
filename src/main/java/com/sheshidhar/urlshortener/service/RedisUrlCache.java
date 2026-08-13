package com.sheshidhar.urlshortener.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import com.sheshidhar.urlshortener.dto.CachedUrl;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.CacheInvalidationException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.util.Optional;

@Component
public class RedisUrlCache implements UrlCache, UrlCacheInvalidator {

    private static final Logger log = LoggerFactory.getLogger(RedisUrlCache.class);
    private static final String KEY_PREFIX = "short-url:";

    private final StringRedisTemplate redisTemplate;
    private final ObjectMapper objectMapper;
    private final UrlShortenerProperties properties;
    private final Clock clock;

    public RedisUrlCache(
            StringRedisTemplate redisTemplate,
            ObjectMapper objectMapper,
            UrlShortenerProperties properties,
            Clock clock
    ) {
        this.redisTemplate = redisTemplate;
        this.objectMapper = objectMapper;
        this.properties = properties;
        this.clock = clock;
    }

    @Override
    public Optional<CachedUrl> find(String shortCode) {
        try {
            String cachedValue = redisTemplate.opsForValue().get(key(shortCode));
            return cachedValue == null
                    ? Optional.empty()
                    : Optional.of(objectMapper.readValue(cachedValue, CachedUrl.class));
        } catch (JsonProcessingException | DataAccessException cacheFailure) {
            log.warn("Redis lookup or cache decoding failed; falling back to PostgreSQL for short code {} ({})",
                    shortCode, cacheFailure.getClass().getSimpleName());
            log.debug("Redis lookup failure details for short code {}", shortCode, cacheFailure);
            return Optional.empty();
        }
    }

    @Override
    public void put(UrlMapping mapping) {
        Duration ttl = properties.cacheTtl();
        if (mapping.getExpiresAt() != null) {
            Duration untilExpiration = Duration.between(clock.instant(), mapping.getExpiresAt());
            if (untilExpiration.isZero() || untilExpiration.isNegative()) {
                return;
            }
            if (untilExpiration.compareTo(ttl) < 0) {
                ttl = untilExpiration;
            }
        }

        try {
            String cachedValue = objectMapper.writeValueAsString(CachedUrl.from(mapping));
            redisTemplate.opsForValue().set(key(mapping.getShortCode()), cachedValue, ttl);
        } catch (JsonProcessingException | DataAccessException cacheFailure) {
            log.warn("Redis write failed; redirect remains available from PostgreSQL for short code {} ({})",
                    mapping.getShortCode(), cacheFailure.getClass().getSimpleName());
            log.debug("Redis write failure details for short code {}", mapping.getShortCode(), cacheFailure);
        }
    }

    @Override
    public void evict(String shortCode) {
        try {
            redisTemplate.delete(key(shortCode));
        } catch (DataAccessException cacheFailure) {
            throw new CacheInvalidationException(cacheFailure);
        }
    }

    private String key(String shortCode) {
        return KEY_PREFIX + shortCode;
    }
}
