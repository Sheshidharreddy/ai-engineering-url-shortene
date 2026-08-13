package com.sheshidhar.urlshortener.url;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    private final UrlCreationService urlCreationService;
    private final UrlMetadataService urlMetadataService;
    private final UrlAnalyticsService urlAnalyticsService;

    public UrlController(
            UrlCreationService urlCreationService,
            UrlMetadataService urlMetadataService,
            UrlAnalyticsService urlAnalyticsService
    ) {
        this.urlCreationService = urlCreationService;
        this.urlMetadataService = urlMetadataService;
        this.urlAnalyticsService = urlAnalyticsService;
    }

    @Operation(summary = "Create a short URL")
    @ApiResponse(responseCode = "201", description = "Short URL created")
    @ApiResponse(responseCode = "400", description = "Request validation failed")
    @ApiResponse(responseCode = "409", description = "Custom alias already exists")
    @PostMapping
    public ResponseEntity<CreateUrlResponse> create(@Valid @RequestBody CreateUrlRequest request) {
        CreateUrlResponse response = urlCreationService.create(request);
        return ResponseEntity.created(URI.create(response.shortUrl())).body(response);
    }

    @Operation(summary = "Get short URL metadata")
    @ApiResponse(responseCode = "200", description = "Metadata returned")
    @ApiResponse(responseCode = "400", description = "Short-code syntax is invalid")
    @ApiResponse(responseCode = "404", description = "Short URL does not exist")
    @ApiResponse(responseCode = "503", description = "Primary data store is unavailable")
    @GetMapping("/{shortCode}")
    public UrlMetadataResponse getMetadata(@PathVariable String shortCode) {
        return urlMetadataService.get(shortCode);
    }

    @Operation(summary = "Get short URL analytics")
    @ApiResponse(responseCode = "200", description = "Analytics returned")
    @ApiResponse(responseCode = "400", description = "Short-code syntax is invalid")
    @ApiResponse(responseCode = "404", description = "Short URL does not exist")
    @ApiResponse(responseCode = "503", description = "Primary data store is unavailable")
    @GetMapping("/{shortCode}/analytics")
    public UrlAnalyticsResponse getAnalytics(@PathVariable String shortCode) {
        return urlAnalyticsService.get(shortCode);
    }
}
