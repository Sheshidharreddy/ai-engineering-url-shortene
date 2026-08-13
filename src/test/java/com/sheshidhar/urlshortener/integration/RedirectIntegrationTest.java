package com.sheshidhar.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import com.sheshidhar.urlshortener.dto.CreateUrlRequest;
import com.sheshidhar.urlshortener.dto.CreateUrlResponse;
import com.sheshidhar.urlshortener.dto.CachedUrl;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.AliasAlreadyExistsException;
import com.sheshidhar.urlshortener.mapper.UrlMapper;
import com.sheshidhar.urlshortener.repository.RedirectEventRepository;
import com.sheshidhar.urlshortener.repository.UrlMappingRepository;
import com.sheshidhar.urlshortener.repository.UrlMappingWriter;
import com.sheshidhar.urlshortener.service.RedisUrlCache;
import com.sheshidhar.urlshortener.service.ShortCodeGenerator;
import com.sheshidhar.urlshortener.service.UrlCreationService;
import com.sheshidhar.urlshortener.service.UrlDeletionService;
import com.sheshidhar.urlshortener.validator.DestinationUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

    @Autowired
    private RedisUrlCache redisUrlCache;

    @Autowired
    private UrlDeletionService urlDeletionService;

    @Autowired
    private UrlCreationService urlCreationService;

    @Autowired
    private UrlMappingWriter urlMappingWriter;

    @Autowired
    private DestinationUrlValidator destinationUrlValidator;

    @Autowired
    private UrlShortenerProperties urlShortenerProperties;

    @Autowired
    private UrlMapper urlMapper;

    @Autowired
    private Clock clock;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @BeforeEach
    void cleanState() {
        redirectEventRepository.deleteAll();
        urlMappingRepository.deleteAll();
        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
    }

    @Test
    void createThenCacheMissRedirectsPopulatesRedisAndRecordsAnalytics() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/products/1","customAlias":"product1"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost/product1"))
                .andExpect(jsonPath("$.shortCode").value("product1"));

        mockMvc.perform(get("/product1"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/products/1"));

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(
                        redirectEventRepository.countByShortCode("product1")).isEqualTo(1));
        org.assertj.core.api.Assertions.assertThat(redisTemplate.hasKey("short-url:product1")).isTrue();
    }

    @Test
    void redisOutageFallsBackToPostgresForRedirect() throws Exception {
        urlMappingRepository.saveAndFlush(UrlMapping.create(
                "redisoff",
                "https://example.com/postgres-fallback",
                Instant.now().minusSeconds(60),
                null
        ));
        REDIS.getDockerClient().pauseContainerCmd(REDIS.getContainerId()).exec();

        try {
            mockMvc.perform(get("/redisoff"))
                    .andExpect(status().isFound())
                    .andExpect(header().string("Location", "https://example.com/postgres-fallback"));
        } finally {
            REDIS.getDockerClient().unpauseContainerCmd(REDIS.getContainerId()).exec();
            await().atMost(Duration.ofSeconds(5))
                    .ignoreExceptions()
                    .untilAsserted(() -> {
                        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
                            assertThat(connection.ping()).isEqualTo("PONG");
                        }
                    });
        }
    }

    @Test
    void malformedCachedValueFallsBackToPostgresAndRepairsCache() throws Exception {
        urlMappingRepository.saveAndFlush(UrlMapping.create(
                "badcache",
                "https://example.com/cache-repair",
                Instant.now().minusSeconds(60),
                null
        ));
        redisTemplate.opsForValue().set("short-url:badcache", "{not-json", Duration.ofMinutes(1));

        mockMvc.perform(get("/badcache"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/cache-repair"));

        String repairedValue = redisTemplate.opsForValue().get("short-url:badcache");
        assertThat(repairedValue).isNotNull();
        assertThat(objectMapper.readValue(repairedValue, CachedUrl.class).originalUrl())
                .isEqualTo("https://example.com/cache-repair");
    }

    @Test
    void metadataReadsPostgresWithoutPopulatingRedirectCacheOrAnalytics() throws Exception {
        Instant createdAt = Instant.now().minusSeconds(120);
        Instant expiresAt = Instant.now().minusSeconds(60);
        urlMappingRepository.saveAndFlush(UrlMapping.create(
                "metadata1",
                "https://example.com/metadata",
                createdAt,
                expiresAt
        ));

        mockMvc.perform(get("/api/v1/urls/metadata1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("metadata1"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost/metadata1"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/metadata"))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.expiresAt").exists())
                .andExpect(jsonPath("$.expired").value(true));

        org.assertj.core.api.Assertions.assertThat(redisTemplate.hasKey("short-url:metadata1")).isFalse();
        org.assertj.core.api.Assertions.assertThat(redirectEventRepository.countByShortCode("metadata1")).isZero();
    }

    @Test
    void analyticsAggregatesPersistedRedirectEvents() throws Exception {
        Instant createdAt = Instant.now().minusSeconds(60);
        urlMappingRepository.saveAndFlush(UrlMapping.create(
                "analytic1",
                "https://example.com/analytics",
                createdAt,
                null
        ));

        mockMvc.perform(get("/analytic1"))
                .andExpect(status().isFound());
        mockMvc.perform(get("/analytic1"))
                .andExpect(status().isFound());

        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(
                        redirectEventRepository.countByShortCode("analytic1")).isEqualTo(2));

        mockMvc.perform(get("/api/v1/urls/analytic1/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("analytic1"))
                .andExpect(jsonPath("$.totalClickCount").value(2))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.lastAccessedAt").exists());
    }

    @Test
    void deleteRemovesMappingAnalyticsAndCacheAndAllowsSafeRetry() throws Exception {
        urlMappingRepository.saveAndFlush(UrlMapping.create(
                "delete01",
                "https://example.com/delete",
                Instant.now().minusSeconds(60),
                null
        ));

        mockMvc.perform(get("/delete01"))
                .andExpect(status().isFound());
        await().atMost(Duration.ofSeconds(5))
                .untilAsserted(() -> org.assertj.core.api.Assertions.assertThat(
                        redirectEventRepository.countByShortCode("delete01")).isEqualTo(1));
        org.assertj.core.api.Assertions.assertThat(redisTemplate.hasKey("short-url:delete01")).isTrue();

        mockMvc.perform(delete("/api/v1/urls/delete01"))
                .andExpect(status().isNoContent());

        org.assertj.core.api.Assertions.assertThat(urlMappingRepository.findByShortCode("delete01")).isEmpty();
        org.assertj.core.api.Assertions.assertThat(redirectEventRepository.countByShortCode("delete01")).isZero();
        org.assertj.core.api.Assertions.assertThat(redisTemplate.hasKey("short-url:delete01")).isFalse();
        mockMvc.perform(get("/delete01")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/urls/delete01")).andExpect(status().isNotFound());
        mockMvc.perform(get("/api/v1/urls/delete01/analytics")).andExpect(status().isNotFound());

        mockMvc.perform(delete("/api/v1/urls/delete01"))
                .andExpect(status().isNoContent());

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/reused","customAlias":"delete01"}
                                """))
                .andExpect(status().isCreated());
        mockMvc.perform(get("/api/v1/urls/delete01/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClickCount").value(0))
                .andExpect(jsonPath("$.lastAccessedAt").doesNotExist());
    }

    @Test
    void concurrentCacheMissCannotRepopulateCacheAfterDeletion() throws Exception {
        String shortCode = "delete02";
        UrlMapping mapping = urlMappingRepository.saveAndFlush(UrlMapping.create(
                shortCode,
                "https://example.com/concurrent-delete",
                Instant.now().minusSeconds(60),
                null
        ));
        redisUrlCache.put(mapping);
        assertThat(redisTemplate.hasKey("short-url:" + shortCode)).isTrue();

        CountDownLatch rowLocked = new CountDownLatch(1);
        CountDownLatch populateCache = new CountDownLatch(1);
        CountDownLatch cachePopulated = new CountDownLatch(1);
        CountDownLatch releaseReader = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<?> reader = executor.submit(() -> {
                TransactionTemplate transaction = new TransactionTemplate(transactionManager);
                transaction.executeWithoutResult(status -> {
                    UrlMapping lockedMapping = urlMappingRepository.findByShortCodeForRedirect(shortCode)
                            .orElseThrow();
                    rowLocked.countDown();
                    awaitLatch(populateCache);
                    redisUrlCache.put(lockedMapping);
                    cachePopulated.countDown();
                    awaitLatch(releaseReader);
                });
            });

            awaitLatch(rowLocked);
            Future<?> deletion = executor.submit(() -> urlDeletionService.delete(shortCode));

            await().atMost(Duration.ofSeconds(5)).untilAsserted(() ->
                    assertThat(redisTemplate.hasKey("short-url:" + shortCode)).isFalse());
            populateCache.countDown();
            awaitLatch(cachePopulated);

            assertThat(redisTemplate.hasKey("short-url:" + shortCode)).isTrue();
            assertThat(deletion.isDone()).isFalse();

            releaseReader.countDown();
            reader.get(5, TimeUnit.SECONDS);
            deletion.get(5, TimeUnit.SECONDS);

            assertThat(urlMappingRepository.findByShortCode(shortCode)).isEmpty();
            assertThat(redisTemplate.hasKey("short-url:" + shortCode)).isFalse();
        } finally {
            populateCache.countDown();
            releaseReader.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void actuatorProbesAreAvailable() throws Exception {
        mockMvc.perform(get("/internal/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/internal/actuator/health/liveness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/internal/actuator/health/readiness"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
        mockMvc.perform(get("/internal/actuator/info"))
                .andExpect(status().isNotFound());
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
        assertThat(redisTemplate.hasKey("short-url:expired1")).isFalse();
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

    @Test
    void simultaneousCustomAliasCreationCreatesExactlyOneMapping() throws Exception {
        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<CreationOutcome> first = executor.submit(() -> createAliasAfterStart(ready, start));
            Future<CreationOutcome> second = executor.submit(() -> createAliasAfterStart(ready, start));

            awaitLatch(ready);
            start.countDown();

            assertThat(List.of(first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS)))
                    .containsExactlyInAnyOrder(CreationOutcome.CREATED, CreationOutcome.CONFLICT);
            assertThat(urlMappingRepository.findByShortCode("samealias")).isPresent();
        } finally {
            executor.shutdownNow();
        }
    }

    @Test
    void generatedCodeCollisionRetriesAfterDatabaseConstraintFailure() {
        Instant now = clock.instant();
        urlMappingRepository.saveAndFlush(UrlMapping.create(
                "collision",
                "https://example.com/existing",
                now.minusSeconds(1),
                null
        ));
        AtomicInteger generatedCodes = new AtomicInteger();
        ShortCodeGenerator generator = () -> generatedCodes.getAndIncrement() == 0 ? "collision" : "unique02";
        UrlCreationService collisionAwareService = new UrlCreationService(
                urlMappingRepository,
                urlMappingWriter,
                generator,
                destinationUrlValidator,
                urlShortenerProperties,
                urlMapper,
                clock
        );

        CreateUrlResponse response = collisionAwareService.create(
                new CreateUrlRequest("https://example.com/generated", null, null)
        );

        assertThat(response.shortCode()).isEqualTo("unique02");
        assertThat(generatedCodes).hasValue(2);
        assertThat(urlMappingRepository.findByShortCode("unique02")).isPresent();
    }

    private CreationOutcome createAliasAfterStart(CountDownLatch ready, CountDownLatch start) {
        ready.countDown();
        awaitLatch(start);
        try {
            urlCreationService.create(new CreateUrlRequest(
                    "https://example.com/concurrent-alias",
                    "samealias",
                    null
            ));
            return CreationOutcome.CREATED;
        } catch (AliasAlreadyExistsException conflict) {
            return CreationOutcome.CONFLICT;
        }
    }

    private static void awaitLatch(CountDownLatch latch) {
        try {
            if (!latch.await(5, TimeUnit.SECONDS)) {
                throw new AssertionError("Timed out waiting for concurrent test step");
            }
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
            throw new AssertionError("Concurrent test was interrupted", exception);
        }
    }

    private enum CreationOutcome {
        CREATED,
        CONFLICT
    }
}
