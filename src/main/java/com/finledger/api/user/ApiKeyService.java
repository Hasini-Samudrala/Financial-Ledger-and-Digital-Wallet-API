package com.finledger.api.user;

import org.springframework.stereotype.Service;

import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.HexFormat;
import java.util.UUID;

/**
 * Generates and verifies {@code flw_<userId>.<secret>} API keys using 256-bit
 * random secrets, salted SHA-256 hashing, and constant-time comparison (ADR-002).
 */
@Service
public class ApiKeyService {

    private static final String PREFIX = "flw_";
    private static final int SECRET_BYTES = 32;
    private static final int SALT_BYTES = 32;
    private static final HexFormat HEX = HexFormat.of();

    private final SecureRandom secureRandom = new SecureRandom();

    public record GeneratedKey(String rawApiKey, byte[] hash, byte[] salt) {
    }

    public record ParsedKey(String userId, byte[] secretBytes) {
    }

    public GeneratedKey generateForUser(String userId) {
        byte[] secretBytes = new byte[SECRET_BYTES];
        byte[] salt = new byte[SALT_BYTES];
        secureRandom.nextBytes(secretBytes);
        secureRandom.nextBytes(salt);

        byte[] hash = hashSecret(salt, secretBytes);
        String rawApiKey = PREFIX + userId + "." + HEX.formatHex(secretBytes);
        return new GeneratedKey(rawApiKey, hash, salt);
    }

    /**
     * Parses {@code flw_<userId>.<hexSecret>}. Returns {@code null} if malformed.
     */
    public ParsedKey parse(String rawApiKey) {
        if (rawApiKey == null || !rawApiKey.startsWith(PREFIX)) {
            return null;
        }
        String remainder = rawApiKey.substring(PREFIX.length());
        int dotIdx = remainder.indexOf('.');
        if (dotIdx <= 0 || dotIdx == remainder.length() - 1) {
            return null;
        }
        String userIdPart = remainder.substring(0, dotIdx);
        String secretHex = remainder.substring(dotIdx + 1);
        if (secretHex.length() != SECRET_BYTES * 2) {
            return null;
        }
        try {
            UUID.fromString(userIdPart);
            byte[] secretBytes = HEX.parseHex(secretHex);
            return new ParsedKey(userIdPart, secretBytes);
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    public boolean verify(byte[] suppliedSecret, byte[] storedSalt, byte[] storedHash) {
        if (suppliedSecret == null || storedSalt == null || storedHash == null) {
            return false;
        }
        byte[] candidate = hashSecret(storedSalt, suppliedSecret);
        return MessageDigest.isEqual(candidate, storedHash);
    }

    private static byte[] hashSecret(byte[] salt, byte[] secretBytes) {
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(salt);
            sha256.update(secretBytes);
            return sha256.digest();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable.", ex);
        }
    }
}
