package com.sheshidhar.urlshortener.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sheshidhar.urlshortener.config.UrlShortenerProperties;
import com.sheshidhar.urlshortener.dto.CreateUrlRequest;
import com.sheshidhar.urlshortener.dto.CreateUrlResponse;
import com.sheshidhar.urlshortener.dto.CachedUrl;
import com.sheshidhar.urlshortener.entity.RedirectAnalyticsOutboxEntry;
import com.sheshidhar.urlshortener.entity.RedirectEvent;
import com.sheshidhar.urlshortener.entity.UrlMapping;
import com.sheshidhar.urlshortener.exception.AliasAlreadyExistsException;
import com.sheshidhar.urlshortener.mapper.UrlMapper;
import com.sheshidhar.urlshortener.repository.DatabaseConstraintClassifier;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsOutboxRepository;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsOutboxProcessor;
import com.sheshidhar.urlshortener.repository.RedirectAnalyticsRetentionProcessor;
import com.sheshidhar.urlshortener.repository.RedirectEventRepository;
import com.sheshidhar.urlshortener.repository.UrlMappingRepository;
import com.sheshidhar.urlshortener.repository.UrlMappingWriter;
import com.sheshidhar.urlshortener.service.RedisUrlCache;
import com.sheshidhar.urlshortener.service.RedirectAnalyticsOutboxDispatcher;
import com.sheshidhar.urlshortener.service.ShortCodeGenerator;
import com.sheshidhar.urlshortener.service.UrlCreationService;
import com.sheshidhar.urlshortener.service.UrlDeletionService;
import com.sheshidhar.urlshortener.service.UrlRedirectDatabaseResolver;
import com.sheshidhar.urlshortener.validator.DestinationUrlValidator;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.connection.RedisConnection;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
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
import javax.sql.DataSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.awaitility.Awaitility.await;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
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
        registry.add("app.analytics.dispatch-interval", () -> "1h");
        registry.add("app.analytics.retention-cleanup-interval", () -> "1h");
    }

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private UrlMappingRepository urlMappingRepository;

    @Autowired
    private RedirectEventRepository redirectEventRepository;

    @Autowired
    private RedirectAnalyticsOutboxRepository outboxRepository;

    @Autowired
    private RedirectAnalyticsOutboxDispatcher outboxDispatcher;

    @Autowired
    private RedirectAnalyticsOutboxProcessor outboxProcessor;

    @Autowired
    private RedirectAnalyticsRetentionProcessor retentionProcessor;

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

    @Autowired
    private DatabaseConstraintClassifier constraintClassifier;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private Environment environment;

    @MockitoSpyBean
    private UrlRedirectDatabaseResolver databaseResolver;

    @BeforeEach
    void cleanState() {
        outboxRepository.deleteAllInBatch();
        redirectEventRepository.deleteAllInBatch();
        urlMappingRepository.deleteAllInBatch();
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
                .untilAsserted(() -> {
                    outboxDispatcher.dispatch();
                    org.assertj.core.api.Assertions.assertThat(
                            redirectEventRepository.countByShortCode("product1")).isEqualTo(1);
                });
        org.assertj.core.api.Assertions.assertThat(redisTemplate.hasKey("short-url:product1")).isTrue();
    }

    @Test
    void repeatedCreationWithSameIdempotencyKeyReturnsOriginalMapping() throws Exception {
        String requestBody = """
                {"url":"https://example.com/idempotent","customAlias":"idempotent1"}
                """;

        mockMvc.perform(post("/api/v1/urls")
                        .header("Idempotency-Key", "create-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("idempotent1"));

        mockMvc.perform(post("/api/v1/urls")
                        .header("Idempotency-Key", "create-request-001")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(requestBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.shortCode").value("idempotent1"));

        assertThat(urlMappingRepository.count()).isEqualTo(1);
    }

    @Test
    void idempotencyKeyReuseWithDifferentPayloadReturnsConflict() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .header("Idempotency-Key", "create-request-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/first","customAlias":"idemfirst"}
                                """))
                .andExpect(status().isCreated());

        mockMvc.perform(post("/api/v1/urls")
                        .header("Idempotency-Key", "create-request-002")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/second","customAlias":"idemsecond"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_CONFLICT"));

        assertThat(urlMappingRepository.count()).isEqualTo(1);
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
    void simulatedColdCacheAfterRedisRestartProducesOneDatabaseLoadPerReplica() throws Exception {
        String shortCode = "stampede1";
        urlMappingRepository.saveAndFlush(UrlMapping.create(
                shortCode,
                "https://example.com/after-redis-restart",
                Instant.now().minusSeconds(60),
                null
        ));

        try (RedisConnection connection = redisTemplate.getConnectionFactory().getConnection()) {
            connection.serverCommands().flushDb();
        }
        reset(databaseResolver);

        int requestCount = 16;
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(requestCount);
        try {
            List<Future<Integer>> requests = java.util.stream.IntStream.range(0, requestCount)
                    .mapToObj(ignored -> executor.submit(() -> {
                        awaitLatch(start);
                        return mockMvc.perform(get("/" + shortCode))
                                .andReturn()
                                .getResponse()
                                .getStatus();
                    }))
                    .toList();
            start.countDown();

            for (Future<Integer> request : requests) {
                assertThat(request.get(10, TimeUnit.SECONDS)).isEqualTo(302);
            }

            verify(databaseResolver, times(1)).resolveAndCache(shortCode);
            assertThat(redisTemplate.hasKey("short-url:" + shortCode)).isTrue();
        } finally {
            start.countDown();
            executor.shutdownNow();
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
                .untilAsserted(() -> {
                    outboxDispatcher.dispatch();
                    org.assertj.core.api.Assertions.assertThat(
                            redirectEventRepository.countByShortCode("analytic1")).isEqualTo(2);
                });

        mockMvc.perform(get("/api/v1/urls/analytic1/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("analytic1"))
                .andExpect(jsonPath("$.totalClickCount").value(2))
                .andExpect(jsonPath("$.createdAt").exists())
                .andExpect(jsonPath("$.lastAccessedAt").exists());
    }

    @Test
    void concurrentOutboxDispatchersDoNotDuplicateEvents() throws Exception {
        Instant now = Instant.now();
        outboxRepository.saveAllAndFlush(java.util.stream.IntStream.range(0, 20)
                .mapToObj(index -> RedirectAnalyticsOutboxEntry.create("outbox01", now.plusMillis(index), now))
                .toList());
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService executor = Executors.newFixedThreadPool(2);

        try {
            Future<Integer> first = executor.submit(() -> {
                awaitLatch(start);
                return outboxProcessor.processNextBatch(20);
            });
            Future<Integer> second = executor.submit(() -> {
                awaitLatch(start);
                return outboxProcessor.processNextBatch(20);
            });
            start.countDown();

            assertThat(first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS)).isEqualTo(20);
            assertThat(redirectEventRepository.countByShortCode("outbox01")).isEqualTo(20);
            assertThat(outboxRepository.count()).isZero();
        } finally {
            start.countDown();
            executor.shutdownNow();
        }
    }

    @Test
    void analyticsRetentionDeletesOnlyExpiredEventsWithinBatchLimit() {
        Instant now = Instant.now();
        redirectEventRepository.saveAllAndFlush(List.of(
                RedirectEvent.create("retain01", now.minus(Duration.ofDays(100))),
                RedirectEvent.create("retain01", now.minus(Duration.ofDays(95))),
                RedirectEvent.create("retain01", now.minus(Duration.ofDays(10)))
        ));

        assertThat(retentionProcessor.deleteExpiredBatch(now.minus(Duration.ofDays(90)), 1)).isEqualTo(1);
        assertThat(redirectEventRepository.countByShortCode("retain01")).isEqualTo(2);
        assertThat(retentionProcessor.deleteExpiredBatch(now.minus(Duration.ofDays(90)), 10)).isEqualTo(1);
        assertThat(redirectEventRepository.countByShortCode("retain01")).isEqualTo(1);
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
                .untilAsserted(() -> {
                    outboxDispatcher.dispatch();
                    org.assertj.core.api.Assertions.assertThat(
                            redirectEventRepository.countByShortCode("delete01")).isEqualTo(1);
                });
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
        mockMvc.perform(get("/internal/actuator/prometheus"))
                .andExpect(status().isOk())
                .andExpect(content().string(org.hamcrest.Matchers.containsString("jvm_memory_used_bytes")));
        mockMvc.perform(get("/internal/actuator/info"))
                .andExpect(status().isNotFound());
    }

    @Test
    void databaseDeadlinesAndGracefulShutdownAreConfigured() {
        com.zaxxer.hikari.HikariDataSource hikariDataSource = (com.zaxxer.hikari.HikariDataSource) dataSource;
        assertThat(hikariDataSource.getConnectionTimeout()).isEqualTo(3_000);
        assertThat(hikariDataSource.getDataSourceProperties().getProperty("connectTimeout")).isEqualTo("3");
        assertThat(hikariDataSource.getDataSourceProperties().getProperty("socketTimeout")).isEqualTo("10");
        assertThat(new JdbcTemplate(dataSource).queryForObject("SHOW statement_timeout", String.class))
                .isEqualTo("5s");
        assertThat(new JdbcTemplate(dataSource).queryForObject("SHOW lock_timeout", String.class))
                .isEqualTo("3s");
        assertThat(environment.getProperty("server.shutdown")).isEqualTo("graceful");
        assertThat(environment.getProperty("spring.lifecycle.timeout-per-shutdown-phase")).isEqualTo("45s");
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
                constraintClassifier,
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
