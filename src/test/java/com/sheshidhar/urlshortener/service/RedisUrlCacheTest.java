package com.sheshidhar.urlshortener.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import com.sheshidhar.urlshortener.dto.CachedUrl;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.CacheInvalidationException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RedisUrlCacheTest {

    private static final Instant NOW = Instant.parse("2026-08-12T18:00:00Z");

    private final StringRedisTemplate redisTemplate = mock(StringRedisTemplate.class);
    private final ValueOperations<String, String> valueOperations = mock(ValueOperations.class);
    private final ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
    private RedisUrlCache cache;

    @BeforeEach
    void setUp() {
        when(redisTemplate.opsForValue()).thenReturn(valueOperations);
        cache = new RedisUrlCache(
                redisTemplate,
                objectMapper,
                new UrlShortenerProperties("http://localhost:8080", 8, 5, Duration.ofHours(24)),
                Clock.fixed(NOW, ZoneOffset.UTC)
        );
    }

    @Test
    void readsExpiryAwareValueFromRedis() throws Exception {
        CachedUrl expected = new CachedUrl("https://example.com", NOW.plusSeconds(60));
        when(valueOperations.get("short-url:abcd1234"))
                .thenReturn(objectMapper.writeValueAsString(expected));

        assertThat(cache.find("abcd1234")).contains(expected);
    }

    @Test
    void capsCacheTtlAtUrlExpiration() {
        UrlMapping mapping = UrlMapping.create(
                "abcd1234",
                "https://example.com",
                NOW,
                NOW.plus(Duration.ofMinutes(15))
        );

        cache.put(mapping);

        ArgumentCaptor<Duration> ttl = ArgumentCaptor.forClass(Duration.class);
        verify(valueOperations).set(eq("short-url:abcd1234"), any(String.class), ttl.capture());
        assertThat(ttl.getValue()).isEqualTo(Duration.ofMinutes(15));
    }

    @Test
    void treatsRedisFailureAsCacheMiss() {
        when(valueOperations.get("short-url:abcd1234"))
                .thenThrow(new RedisConnectionFailureException("offline"));

        assertThat(cache.find("abcd1234")).isEmpty();
    }

    @Test
    void doesNotHideUnexpectedProgrammingFailure() {
        when(valueOperations.get("short-url:abcd1234"))
                .thenThrow(new IllegalStateException("unexpected bug"));

        assertThatThrownBy(() -> cache.find("abcd1234"))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("unexpected bug");
    }

    @Test
    void treatsMalformedCachedValueAsCacheMiss() {
        when(valueOperations.get("short-url:abcd1234")).thenReturn("{not-json");

        assertThat(cache.find("abcd1234")).isEmpty();
    }

    @Test
    void ignoresRedisWriteFailure() {
        doThrow(new RedisConnectionFailureException("offline"))
                .when(valueOperations).set(eq("short-url:abcd1234"), any(String.class), any(Duration.class));
        UrlMapping mapping = UrlMapping.create("abcd1234", "https://example.com", NOW, null);

        assertThatCode(() -> cache.put(mapping)).doesNotThrowAnyException();
    }

    @Test
    void evictsCachedUrl() {
        cache.evict("abcd1234");

        verify(redisTemplate).delete("short-url:abcd1234");
    }

    @Test
    void exposesEvictionFailureToDeletionCallers() {
        when(redisTemplate.delete("short-url:abcd1234"))
                .thenThrow(new RedisConnectionFailureException("offline"));

        assertThatThrownBy(() -> cache.evict("abcd1234"))
                .isInstanceOf(CacheInvalidationException.class);
    }
}
