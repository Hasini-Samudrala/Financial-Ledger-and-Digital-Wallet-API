package com.finledger.api.ledger;

import com.finledger.api.config.AppProperties;
import org.springframework.stereotype.Service;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;

/**
 * Implements the ADR-004 SHA-256 ledger hash chain and HMAC-SHA-256 checkpoint.
 */
@Service
public class LedgerHashChainService {

    public static final byte[] GENESIS_HASH = new byte[32];

    private final byte[] hmacSecret;

    public LedgerHashChainService(AppProperties properties) {
        this.hmacSecret = properties.getDecodedHmacSecret();
    }

    /**
     * Canonical binary encoding per ADR-004:
     * <ol>
     *   <li>{@code sequence_no}: 8-byte big-endian int64</li>
     *   <li>{@code entry_id}: 4-byte length prefix + UTF-8</li>
     *   <li>{@code transaction_id}: 4-byte length prefix + UTF-8</li>
     *   <li>{@code account_id}: 4-byte length prefix + UTF-8</li>
     *   <li>{@code currency_code}: 4-byte length prefix + UTF-8</li>
     *   <li>{@code debit_minor}: 8-byte big-endian int64</li>
     *   <li>{@code credit_minor}: 8-byte big-endian int64</li>
     *   <li>{@code created_at}: 4-byte length prefix + UTF-8 (ISO 8601 UTC)</li>
     * </ol>
     */
    public byte[] canonicalEncode(
            long sequenceNo,
            String entryId,
            String transactionId,
            String accountId,
            String currencyCode,
            long debitMinor,
            long creditMinor,
            String createdAt) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            out.writeLong(sequenceNo);
            writeLengthPrefixedString(out, entryId);
            writeLengthPrefixedString(out, transactionId);
            writeLengthPrefixedString(out, accountId);
            writeLengthPrefixedString(out, currencyCode);
            out.writeLong(debitMinor);
            out.writeLong(creditMinor);
            writeLengthPrefixedString(out, createdAt);
            out.flush();
            return buf.toByteArray();
        } catch (IOException ex) {
            throw new IllegalStateException("Failed to encode ledger entry canonically.", ex);
        }
    }

    public byte[] computeEntryHash(byte[] previousHash, byte[] canonicalEntryBytes) {
        if (previousHash == null || previousHash.length != 32) {
            throw new IllegalArgumentException("previousHash must be 32 bytes.");
        }
        try {
            MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
            sha256.update(previousHash);
            sha256.update(canonicalEntryBytes);
            return sha256.digest();
        } catch (GeneralSecurityException ex) {
            throw new IllegalStateException("SHA-256 unavailable.", ex);
        }
    }

    /**
     * Computes HMAC-SHA-256 over {@code last_entry_sequence (8B big-endian) || chain_head_hash (32B)}.
     */
    public byte[] computeCheckpointMac(long lastEntrySequence, byte[] chainHeadHash) {
        if (chainHeadHash == null || chainHeadHash.length != 32) {
            throw new IllegalArgumentException("chainHeadHash must be 32 bytes.");
        }
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream(40);
            DataOutputStream out = new DataOutputStream(buf);
            out.writeLong(lastEntrySequence);
            out.write(chainHeadHash);
            out.flush();

            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(hmacSecret, "HmacSHA256"));
            return mac.doFinal(buf.toByteArray());
        } catch (GeneralSecurityException | IOException ex) {
            throw new IllegalStateException("Failed to compute checkpoint MAC.", ex);
        }
    }

    private static void writeLengthPrefixedString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
