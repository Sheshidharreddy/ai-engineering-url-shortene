package com.sheshidhar.urlshortener.controller;

import com.sheshidhar.urlshortener.config.ApplicationConfig;
import com.sheshidhar.urlshortener.exception.ApiExceptionHandler;
import com.sheshidhar.urlshortener.exception.InvalidShortCodeException;
import com.sheshidhar.urlshortener.exception.UrlExpiredException;
import com.sheshidhar.urlshortener.exception.UrlNotFoundException;
import com.sheshidhar.urlshortener.service.UrlRedirectService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.CannotCreateTransactionException;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(RedirectController.class)
@Import({ApiExceptionHandler.class, ApplicationConfig.class})
class RedirectControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private UrlRedirectService redirectService;

    @Test
    void returnsFoundWithDestinationLocation() throws Exception {
        when(redirectService.resolve("abcd1234")).thenReturn("https://example.com/products/1");

        mockMvc.perform(get("/abcd1234"))
                .andExpect(status().isFound())
                .andExpect(header().string("Location", "https://example.com/products/1"));
    }

    @Test
    void returnsBadRequestForMalformedCode() throws Exception {
        when(redirectService.resolve("bad!")).thenThrow(new InvalidShortCodeException());

        mockMvc.perform(get("/bad!"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
    }

    @Test
    void returnsNotFoundForUnknownCode() throws Exception {
        when(redirectService.resolve("missing1")).thenThrow(new UrlNotFoundException("missing1"));

        mockMvc.perform(get("/missing1"))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("SHORT_URL_NOT_FOUND"));
    }

    @Test
    void returnsGoneForExpiredCode() throws Exception {
        when(redirectService.resolve("expired1")).thenThrow(new UrlExpiredException("expired1"));

        mockMvc.perform(get("/expired1"))
                .andExpect(status().isGone())
                .andExpect(jsonPath("$.code").value("SHORT_URL_EXPIRED"));
    }

    @Test
    void returnsServiceUnavailableWhenPrimaryDatabaseCannotBeReached() throws Exception {
        when(redirectService.resolve("missing1"))
                .thenThrow(new CannotCreateTransactionException("database offline"));

        mockMvc.perform(get("/missing1"))
                .andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("DATABASE_UNAVAILABLE"));
    }
}
