package com.sheshidhar.urlshortener.controller;

import com.sheshidhar.urlshortener.config.ApplicationConfig;
import com.sheshidhar.urlshortener.dto.CreateUrlRequest;
import com.sheshidhar.urlshortener.dto.CreateUrlResponse;
import com.sheshidhar.urlshortener.dto.UrlAnalyticsResponse;
import com.sheshidhar.urlshortener.dto.UrlMetadataResponse;
import com.sheshidhar.urlshortener.exception.AliasAlreadyExistsException;
import com.sheshidhar.urlshortener.exception.ApiExceptionHandler;
import com.sheshidhar.urlshortener.exception.CacheInvalidationException;
import com.sheshidhar.urlshortener.exception.ShortCodeGenerationException;
import com.sheshidhar.urlshortener.exception.UrlNotFoundException;
import com.sheshidhar.urlshortener.service.UrlAnalyticsService;
import com.sheshidhar.urlshortener.service.UrlCreationService;
import com.sheshidhar.urlshortener.service.UrlDeletionService;
import com.sheshidhar.urlshortener.service.UrlMetadataService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import java.time.Instant;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(UrlController.class)
@Import({ApiExceptionHandler.class, ApplicationConfig.class})
class UrlControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlCreationService creationService;

    @MockitoBean
    private UrlMetadataService metadataService;

    @MockitoBean
    private UrlAnalyticsService analyticsService;

    @MockitoBean
    private UrlDeletionService deletionService;

    @Test
    void returnsCreatedResponseAndLocation() throws Exception {
        Instant createdAt = Instant.parse("2026-08-12T18:00:00Z");
        when(creationService.create(any(CreateUrlRequest.class))).thenReturn(new CreateUrlResponse(
                "product123",
                "http://localhost:8080/product123",
                "https://example.com/products/123",
                createdAt,
                null
        ));

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com/products/123","customAlias":"product123"}
                                """))
                .andExpect(status().isCreated())
                .andExpect(header().string("Location", "http://localhost:8080/product123"))
                .andExpect(jsonPath("$.shortCode").value("product123"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/products/123"));
    }

    @Test
    void returnsValidationProblemForInvalidAlias() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com","customAlias":"!"}
                                """))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors").isArray());
    }

    @Test
    void returnsMalformedRequestProblemForInvalidJson() throws Exception {
        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"url\":"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void returnsValidationProblemForOversizedUrl() throws Exception {
        String oversizedUrl = "https://example.com/" + "a".repeat(2_048);

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"%s"}
                                """.formatted(oversizedUrl)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"))
                .andExpect(jsonPath("$.fieldErrors[0].field").value("url"));

        verifyNoInteractions(creationService);
    }

    @Test
    void returnsConflictForDuplicateAlias() throws Exception {
        when(creationService.create(any(CreateUrlRequest.class)))
                .thenThrow(new AliasAlreadyExistsException("product123"));

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com","customAlias":"product123"}
                                """))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ALIAS_ALREADY_EXISTS"));
    }

    @Test
    void returnsServiceUnavailableWhenUniqueCodeCannotBeAllocated() throws Exception {
        when(creationService.create(any(CreateUrlRequest.class)))
                .thenThrow(new ShortCodeGenerationException());

        mockMvc.perform(post("/api/v1/urls")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"url":"https://example.com"}
                                """))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("SHORT_CODE_UNAVAILABLE"));
    }

    @Test
    void returnsUrlMetadata() throws Exception {
        Instant createdAt = Instant.parse("2026-08-12T18:00:00Z");
        when(metadataService.get("product123")).thenReturn(new UrlMetadataResponse(
                "product123",
                "http://localhost:8080/product123",
                "https://example.com/products/123",
                createdAt,
                null,
                false
        ));

        mockMvc.perform(get("/api/v1/urls/product123"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("product123"))
                .andExpect(jsonPath("$.shortUrl").value("http://localhost:8080/product123"))
                .andExpect(jsonPath("$.originalUrl").value("https://example.com/products/123"))
                .andExpect(jsonPath("$.createdAt").value("2026-08-12T18:00:00Z"))
                .andExpect(jsonPath("$.expiresAt").doesNotExist())
                .andExpect(jsonPath("$.expired").value(false));

        verifyNoInteractions(creationService);
    }

    @Test
    void returnsNotFoundForUnknownMetadata() throws Exception {
        when(metadataService.get("missing1")).thenThrow(new UrlNotFoundException("missing1"));

        mockMvc.perform(get("/api/v1/urls/missing1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHORT_URL_NOT_FOUND"));
    }

    @Test
    void returnsUrlAnalytics() throws Exception {
        when(analyticsService.get("product123")).thenReturn(new UrlAnalyticsResponse(
                "product123",
                7,
                Instant.parse("2026-08-12T18:00:00Z"),
                Instant.parse("2026-08-12T19:00:00Z")
        ));

        mockMvc.perform(get("/api/v1/urls/product123/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.shortCode").value("product123"))
                .andExpect(jsonPath("$.totalClickCount").value(7))
                .andExpect(jsonPath("$.createdAt").value("2026-08-12T18:00:00Z"))
                .andExpect(jsonPath("$.lastAccessedAt").value("2026-08-12T19:00:00Z"));
    }

    @Test
    void returnsAnalyticsWithNoLastAccessForUnusedUrl() throws Exception {
        when(analyticsService.get("unused01")).thenReturn(new UrlAnalyticsResponse(
                "unused01",
                0,
                Instant.parse("2026-08-12T18:00:00Z"),
                null
        ));

        mockMvc.perform(get("/api/v1/urls/unused01/analytics"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.totalClickCount").value(0))
                .andExpect(jsonPath("$.lastAccessedAt").doesNotExist());
    }

    @Test
    void deletesUrlIdempotently() throws Exception {
        mockMvc.perform(delete("/api/v1/urls/product123"))
                .andExpect(status().isNoContent());
    }

    @Test
    void returnsServiceUnavailableWhenCacheCannotBeInvalidated() throws Exception {
        doThrow(new CacheInvalidationException(new IllegalStateException("offline")))
                .when(deletionService).delete("product123");

        mockMvc.perform(delete("/api/v1/urls/product123"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("CACHE_UNAVAILABLE"));
    }
}
