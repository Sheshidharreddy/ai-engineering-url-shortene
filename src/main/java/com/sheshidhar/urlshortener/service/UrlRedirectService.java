package com.sheshidhar.urlshortener.service;

import com.sheshidhar.urlshortener.dto.CachedUrl;
import com.sheshidhar.urlshortener.exception.UrlExpiredException;
import com.sheshidhar.urlshortener.validator.ShortCodeValidator;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

@Service
public class UrlRedirectService {

    private static final Logger log = LoggerFactory.getLogger(UrlRedirectService.class);

    private final UrlCache cache;
    private final UrlRedirectDatabaseResolver databaseResolver;
    private final ShortCodeValidator shortCodeValidator;
    private final RedirectAnalyticsRecorder analyticsRecorder;
    private final Clock clock;
    private final ConcurrentMap<String, CompletableFuture<String>> inFlightCacheMisses = new ConcurrentHashMap<>();

    public UrlRedirectService(
            UrlCache cache,
            UrlRedirectDatabaseResolver databaseResolver,
            ShortCodeValidator shortCodeValidator,
            RedirectAnalyticsRecorder analyticsRecorder,
            Clock clock
    ) {
        this.cache = cache;
        this.databaseResolver = databaseResolver;
        this.shortCodeValidator = shortCodeValidator;
        this.analyticsRecorder = analyticsRecorder;
        this.clock = clock;
    }

    public String resolve(String shortCode) {
        shortCodeValidator.validate(shortCode);
        CachedUrl cachedUrl = cache.find(shortCode).orElse(null);

        String originalUrl = cachedUrl == null
                ? resolveCacheMiss(shortCode)
                : resolveCached(shortCode, cachedUrl);

        recordAnalyticsWithoutAffectingRedirect(shortCode);
        return originalUrl;
    }

    private String resolveCached(String shortCode, CachedUrl cachedUrl) {
        if (cachedUrl.isExpiredAt(clock.instant())) {
            throw new UrlExpiredException(shortCode);
        }
        return cachedUrl.originalUrl();
    }

    private void recordAnalyticsWithoutAffectingRedirect(String shortCode) {
        try {
            analyticsRecorder.record(shortCode, clock.instant());
        } catch (RuntimeException analyticsFailure) {
            log.warn("Unable to durably enqueue redirect analytics for short code {} ({})",
                    shortCode, analyticsFailure.getClass().getSimpleName());
            log.debug("Analytics enqueue failure details for short code {}", shortCode, analyticsFailure);
        }
    }

    private String resolveCacheMiss(String shortCode) {
        CompletableFuture<String> leaderResult = new CompletableFuture<>();
        CompletableFuture<String> existingResult = inFlightCacheMisses.putIfAbsent(shortCode, leaderResult);
        if (existingResult != null) {
            return await(existingResult);
        }

        try {
            String originalUrl = databaseResolver.resolveAndCache(shortCode);
            leaderResult.complete(originalUrl);
            return originalUrl;
        } catch (RuntimeException resolutionFailure) {
            leaderResult.completeExceptionally(resolutionFailure);
            throw resolutionFailure;
        } finally {
            inFlightCacheMisses.remove(shortCode, leaderResult);
        }
    }

    private String await(CompletableFuture<String> result) {
        try {
            return result.join();
        } catch (CompletionException failure) {
            if (failure.getCause() instanceof RuntimeException runtimeFailure) {
                throw runtimeFailure;
            }
            throw failure;
        }
    }
}
