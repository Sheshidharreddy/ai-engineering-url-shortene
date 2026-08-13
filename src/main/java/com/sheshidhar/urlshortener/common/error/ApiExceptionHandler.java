package com.sheshidhar.urlshortener.common.error;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

import java.net.URI;
import java.time.Clock;
import java.util.List;
import java.util.Map;

@RestControllerAdvice
public class ApiExceptionHandler {

    private final Clock clock;

    public ApiExceptionHandler(Clock clock) {
        this.clock = clock;
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<ProblemDetail> handleValidation(MethodArgumentNotValidException exception) {
        List<Map<String, String>> fieldErrors = exception.getBindingResult().getFieldErrors().stream()
                .map(this::toFieldError)
                .toList();
        ProblemDetail problem = problem(
                HttpStatus.BAD_REQUEST,
                "VALIDATION_FAILED",
                "Request validation failed",
                "One or more request fields are invalid"
        );
        problem.setProperty("fieldErrors", fieldErrors);
        return ResponseEntity.badRequest().body(problem);
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    ResponseEntity<ProblemDetail> handleUnreadableMessage() {
        return response(HttpStatus.BAD_REQUEST, "MALFORMED_REQUEST", "Malformed request",
                "Request body is missing or contains invalid JSON values");
    }

    @ExceptionHandler({
            InvalidDestinationUrlException.class,
            InvalidCustomAliasException.class,
            InvalidShortCodeException.class,
            IllegalArgumentException.class
    })
    ResponseEntity<ProblemDetail> handleInvalidInput(RuntimeException exception) {
        return response(HttpStatus.BAD_REQUEST, "INVALID_REQUEST", "Invalid request", exception.getMessage());
    }

    @ExceptionHandler(AliasAlreadyExistsException.class)
    ResponseEntity<ProblemDetail> handleAliasConflict(AliasAlreadyExistsException exception) {
        return response(HttpStatus.CONFLICT, "ALIAS_ALREADY_EXISTS", "Alias already exists", exception.getMessage());
    }

    @ExceptionHandler(UrlNotFoundException.class)
    ResponseEntity<ProblemDetail> handleNotFound(UrlNotFoundException exception) {
        return response(HttpStatus.NOT_FOUND, "SHORT_URL_NOT_FOUND", "Short URL not found", exception.getMessage());
    }

    @ExceptionHandler(UrlExpiredException.class)
    ResponseEntity<ProblemDetail> handleExpired(UrlExpiredException exception) {
        return response(HttpStatus.GONE, "SHORT_URL_EXPIRED", "Short URL expired", exception.getMessage());
    }

    @ExceptionHandler(ShortCodeGenerationException.class)
    ResponseEntity<ProblemDetail> handleGenerationFailure(ShortCodeGenerationException exception) {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "SHORT_CODE_UNAVAILABLE",
                "Short code temporarily unavailable", exception.getMessage());
    }

    @ExceptionHandler({DataAccessResourceFailureException.class, CannotCreateTransactionException.class})
    ResponseEntity<ProblemDetail> handleDatabaseUnavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "DATABASE_UNAVAILABLE",
                "Service temporarily unavailable", "The primary data store is temporarily unavailable");
    }

1    @ExceptionHandler(CacheInvalidationException.class)
    ResponseEntity<ProblemDetail> handleCacheUnavailable() {
        return response(HttpStatus.SERVICE_UNAVAILABLE, "CACHE_UNAVAILABLE",
                "Service temporarily unavailable", "The URL cache could not be invalidated");
    }

    private ResponseEntity<ProblemDetail> response(
            HttpStatus status,
            String code,
            String title,
            String detail
    ) {
        return ResponseEntity.status(status).body(problem(status, code, title, detail));
    }

    private ProblemDetail problem(HttpStatus status, String code, String title, String detail) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
        problem.setTitle(title);
        problem.setType(URI.create("urn:problem:url-shortener:" + code.toLowerCase().replace('_', '-')));
        problem.setProperty("code", code);
        problem.setProperty("timestamp", clock.instant());
        return problem;
    }

    private Map<String, String> toFieldError(FieldError fieldError) {
        return Map.of(
                "field", fieldError.getField(),
                "message", fieldError.getDefaultMessage() == null ? "invalid value" : fieldError.getDefaultMessage()
        );
    }
}
