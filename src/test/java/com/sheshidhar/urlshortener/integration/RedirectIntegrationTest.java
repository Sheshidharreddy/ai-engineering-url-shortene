package com.sheshidhar.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheshidhar.urlshortener.url.CachedUrl;
import com.sheshidhar.urlshortener.url.RedirectEventRepository;
import com.sheshidhar.urlshortener.url.UrlMapping;
import com.sheshidhar.urlshortener.url.UrlMappingRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Duration;
import java.time.Instant;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@Testcontainers(disabledWithoutDocker = true)
@SpringBootTest
@AutoConfigureMockMvc
class RedirectIntegrationTest {

    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:17-alpine")
            .withDatabaseName("url_shortener")
            .withUsername("test")
            .withPassword("test");

    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    @DynamicPropertySource
    static void containerProperties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getMappedPort(6379));
        registry.add("app.url-shortener.base-url", () -> "http://localhost");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private RedirectEventRepository redirectEventRepository;

    @Autowired
    private StringRedisTemplate redisTemplate;

    @Autowired
    private ObjectMapper objectMapper;

    @BeforeEach
    void cleanState() {
        redirectEventRepository.deleteAll();
        urlMappingRepository.deleteAll();
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void cacheMissLoadsPostgresPopulatesRedisAndRecordsAnalytics() throws Exception {
        Instant now = Instant.now();
        urlMappingRepository.saveAndFlush(UrlMapping.create(
                "product1",
                "https://example.com/products/1",
                now,
                now.plus(Duration.ofHours(1))
        ));

        mockMvc.perform(get("/product1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/products/1"));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(
                        redirectEventRepository.countByShortCode("product1")).isEqualTo(1));
        org.assertj.core.api.Assertions.assertThat(redisTemplate.hasKey("short-url:product1")).isTrue();
    }

    @Test
    void redisHitDoesNotRequirePostgresMapping() throws Exception {
        CachedUrl cachedUrl = new CachedUrl("https://example.com/from-cache", Instant.now().plusSeconds(60));
        redisTemplate.opsForValue().set(
                "short-url:cached01",
                objectMapper.writeValueAsString(cachedUrl),
                Duration.ofMinutes(1)
        );

        mockMvc.perform(get("/cached01"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/from-cache"));
    }

    @Test
    void expiredMappingReturnsGone() throws Exception {
        Instant now = Instant.now();
        urlMappingRepository.saveAndFlush(UrlMapping.create(
                "expired1",
                "https://example.com/old",
                now.minus(Duration.ofHours(2)),
                now.minus(Duration.ofHours(1))
        ));

        mockMvc.perform(get("/expired1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SHORT_URL_EXPIRED"));
    }

    @Test
    void malformedAndMissingCodesHaveDifferentClientErrors() throws Exception {
        mockMvc.perform(get("/bad!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));

        mockMvc.perform(get("/missing1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHORT_URL_NOT_FOUND"));
    }

    @Test
    void databaseEnforcesShortCodeUniqueness() {
        Instant now = Instant.now();
        urlMappingRepository.saveAndFlush(UrlMapping.create("unique01", "https://example.com/1", now, null));

        assertThatThrownBy(() -> urlMappingRepository.saveAndFlush(
                UrlMapping.create("unique01", "https://example.com/2", now, null)))
                .isInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
    }
}
