package com.finledger.api.exception;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingRequestHeaderException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

import java.util.Map;

/**
 * Centralised exception-to-JSON-envelope mapping (ADR-006).
 * Never exposes stack traces, SQL text, or secrets.
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

    @ExceptionHandler(DatabaseBusyException.class)
    public ResponseEntity<Map<String, Object>> handleBusy(DatabaseBusyException ex) {
        HttpHeaders headers = new HttpHeaders();
        headers.set("Retry-After", "1");
        return new ResponseEntity<>(envelope(ex.getCode(), ex.getMessage()), headers, ex.getStatus());
    }

    @ExceptionHandler(ApiException.class)
    public ResponseEntity<Map<String, Object>> handleApi(ApiException ex) {
        return ResponseEntity.status(ex.getStatus())
                .body(envelope(ex.getCode(), ex.getMessage()));
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    public ResponseEntity<Map<String, Object>> handleValidation(MethodArgumentNotValidException ex) {
        String message = ex.getBindingResult().getFieldErrors().stream()
                .findFirst()
                .map(err -> err.getField() + ": " + err.getDefaultMessage())
                .orElse("Request validation failed.");
        return ResponseEntity.badRequest().body(envelope("VALIDATION_ERROR", message));
    }

    @ExceptionHandler({
            HttpMessageNotReadableException.class,
            MissingRequestHeaderException.class,
            MethodArgumentTypeMismatchException.class
    })
    public ResponseEntity<Map<String, Object>> handleBadRequest(Exception ex) {
        return ResponseEntity.badRequest()
                .body(envelope("VALIDATION_ERROR", "Malformed or incomplete request."));
    }

    @ExceptionHandler(Exception.class)
    public ResponseEntity<Map<String, Object>> handleUnexpected(Exception ex) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(envelope("INTERNAL_ERROR", "An unexpected error occurred."));
    }

    public static Map<String, Object> envelope(String code, String message) {
        return Map.of("error", Map.of("code", code, "message", message));
    }
}
