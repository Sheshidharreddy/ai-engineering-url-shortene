package com.sheshidhar.urlshortener.url;

import com.sheshidhar.urlshortener.common.error.UrlExpiredException;
import com.sheshidhar.urlshortener.common.error.UrlNotFoundException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;

@Service
public class UrlRedirectService {

    private static final Logger log = LoggerFactory.getLogger(UrlRedirectService.class);

    private final UrlMappingRepository repository;
    private final UrlCache cache;
    private final ShortCodeValidator shortCodeValidator;
    private final RedirectAnalyticsRecorder analyticsRecorder;
    private final Clock clock;

    public UrlRedirectService(
            UrlMappingRepository repository,
            UrlCache cache,
            ShortCodeValidator shortCodeValidator,
            RedirectAnalyticsRecorder analyticsRecorder,
            Clock clock
    ) {
        this.repository = repository;
        this.cache = cache;
        this.shortCodeValidator = shortCodeValidator;
        this.analyticsRecorder = analyticsRecorder;
        this.clock = clock;
    }

    public String resolve(String shortCode) {
        shortCodeValidator.validate(shortCode);
        CachedUrl cachedUrl = cache.find(shortCode).orElse(null);

        String originalUrl = cachedUrl == null
                ? resolveFromDatabase(shortCode)
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

    private String resolveFromDatabase(String shortCode) {
        UrlMapping mapping = repository.findByShortCode(shortCode)
                .orElseThrow(() -> new UrlNotFoundException(shortCode));

        if (mapping.isExpiredAt(clock.instant())) {
            throw new UrlExpiredException(shortCode);
        }

        cache.put(mapping);
        return mapping.getOriginalUrl();
    }

    private void recordAnalyticsWithoutAffectingRedirect(String shortCode) {
        try {
            analyticsRecorder.record(shortCode, clock.instant());
        } catch (RuntimeException submissionFailure) {
            // A full executor queue can reject before the async method starts; redirects must still succeed.
            log.warn("Unable to submit redirect analytics for short code {}", shortCode, submissionFailure);
        }
    }
}
