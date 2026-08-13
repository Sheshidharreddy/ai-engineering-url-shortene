package com.sheshidhar.urlshortener.url;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import jakarta.validation.Valid;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
@RequestMapping("/api/v1/urls")
public class UrlController {

    private final UrlCreationService urlCreationService;

    public UrlController(UrlCreationService urlCreationService) {
        this.urlCreationService = urlCreationService;
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
}
