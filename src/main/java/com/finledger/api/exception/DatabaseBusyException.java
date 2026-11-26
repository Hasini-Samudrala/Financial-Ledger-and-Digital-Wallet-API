package com.finledger.api.exception;

import org.springframework.http.HttpStatus;

/**
 * Thrown when SQLite write-lock acquisition exhausts bounded retries (ADR-003).
 */
public class DatabaseBusyException extends ApiException {

    public DatabaseBusyException(String message) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "SERVICE_UNAVAILABLE", message);
    }
}
