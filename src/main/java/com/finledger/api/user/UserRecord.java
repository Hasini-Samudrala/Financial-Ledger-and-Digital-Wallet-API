package com.finledger.api.user;

/**
 * Stored user credentials record (salted SHA-256 hash only; never plaintext).
 */
public record UserRecord(
        String userId,
        String username,
        byte[] apiKeyHash,
        byte[] apiKeySalt
) {
}
