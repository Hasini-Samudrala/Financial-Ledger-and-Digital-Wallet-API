package com.finledger.api.transfer;

/**
 * Stored idempotency record scoped by {@code (userId, idempotencyKey)}.
 */
public record IdempotencyRecord(
        String userId,
        String idempotencyKey,
        byte[] requestFingerprint,
        String transferId,
        String createdAt
) {
}
