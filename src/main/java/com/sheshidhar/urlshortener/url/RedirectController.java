package com.sheshidhar.urlshortener.url;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.net.URI;

@RestController
public class RedirectController {

    private final UrlRedirectService redirectService;

    public RedirectController(UrlRedirectService redirectService) {
        this.redirectService = redirectService;
    }

    @Operation(summary = "Redirect a short URL to its destination")
    @ApiResponse(responseCode = "302", description = "Redirect to the original URL")
    @ApiResponse(responseCode = "400", description = "Short code syntax is invalid")
    @ApiResponse(responseCode = "404", description = "Short code does not exist")
    @ApiResponse(responseCode = "410", description = "Short URL existed but has expired")
    @GetMapping("/{shortCode}")
    public ResponseEntity<Void> redirect(@PathVariable String shortCode) {
        return ResponseEntity.status(HttpStatus.FOUND)
                .location(URI.create(redirectService.resolve(shortCode)))
                .build();
    }
}
