package com.finledger.api.transfer;

import com.finledger.api.config.SupportedCurrencies;
import com.finledger.api.db.SqliteTransactionExecutor;
import com.finledger.api.exception.ApiException;
import com.finledger.api.ledger.LedgerEntryDraft;
import com.finledger.api.ledger.LedgerHashChainService;
import com.finledger.api.ledger.LedgerRepository;
import com.finledger.api.wallet.WalletRecord;
import com.finledger.api.wallet.WalletRepository;
import com.finledger.api.wallet.WalletService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Processes idempotent wallet-to-wallet transfers with {@code BEGIN IMMEDIATE}
 * write locking, double-entry ledger recording, and atomic checkpoint updates.
 */
@Service
public class TransferService {

    public record TransferExecutionResult(TransferRecord transfer, boolean replayed) {
    }

    private final TransferRepository transferRepository;
    private final IdempotencyRepository idempotencyRepository;
    private final WalletRepository walletRepository;
    private final LedgerRepository ledgerRepository;
    private final LedgerHashChainService hashChainService;
    private final SupportedCurrencies supportedCurrencies;
    private final SqliteTransactionExecutor txExecutor;

    public TransferService(
            TransferRepository transferRepository,
            IdempotencyRepository idempotencyRepository,
            WalletRepository walletRepository,
            LedgerRepository ledgerRepository,
            LedgerHashChainService hashChainService,
            SupportedCurrencies supportedCurrencies,
            SqliteTransactionExecutor txExecutor) {
        this.transferRepository = transferRepository;
        this.idempotencyRepository = idempotencyRepository;
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.hashChainService = hashChainService;
        this.supportedCurrencies = supportedCurrencies;
        this.txExecutor = txExecutor;
    }

    public TransferExecutionResult createTransfer(
            String authenticatedUserId,
            String idempotencyKey,
            String sourceWalletId,
            String destinationWalletId,
            String rawCurrencyCode,
            Long amountMinor) {
        validateIdempotencyKey(idempotencyKey);
        WalletService.validateUuid(sourceWalletId, "sourceWalletId");
        WalletService.validateUuid(destinationWalletId, "destinationWalletId");
        if (sourceWalletId.equals(destinationWalletId)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "sourceWalletId and destinationWalletId must be different.");
        }
        if (rawCurrencyCode == null || rawCurrencyCode.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY",
                    "Currency code is required.");
        }
        String currencyCode = rawCurrencyCode.trim().toUpperCase();
        if (!supportedCurrencies.isSupported(currencyCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY",
                    "Currency code '" + rawCurrencyCode + "' is not supported.");
        }
        if (amountMinor == null || amountMinor <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "amountMinor must be a positive integer.");
        }

        // Pre-lock existence, ownership, and currency checks (balance is checked under BEGIN IMMEDIATE).
        txExecutor.executeRead(conn -> {
            WalletRecord source = walletRepository.findById(conn, sourceWalletId);
            if (source == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND",
                        "Source wallet not found.");
            }
            if (!authenticatedUserId.equals(source.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "You do not own the source wallet.");
            }
            WalletRecord destination = walletRepository.findById(conn, destinationWalletId);
            if (destination == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND",
                        "Destination wallet not found.");
            }
            if (!currencyCode.equals(source.currencyCode()) || !currencyCode.equals(destination.currencyCode())) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH",
                        "Source and destination wallets must use the requested currency.");
            }
            return null;
        });

        byte[] fingerprint = computeFingerprint(
                authenticatedUserId, sourceWalletId, destinationWalletId, currencyCode, amountMinor);

        return txExecutor.executeWrite(conn -> {
            IdempotencyRecord existing = idempotencyRepository.findByUserAndKey(
                    conn, authenticatedUserId, idempotencyKey);
            if (existing != null) {
                if (MessageDigest.isEqual(existing.requestFingerprint(), fingerprint)) {
                    TransferRecord original = transferRepository.findById(conn, existing.transferId());
                    if (original == null) {
                        throw new IllegalStateException("Idempotency record references missing transfer.");
                    }
                    return new TransferExecutionResult(original, true);
                }
                throw new ApiException(HttpStatus.CONFLICT, "IDEMPOTENCY_CONFLICT",
                        "Idempotency-Key was already used with different transfer parameters.");
            }

            // Authoritative balance check inside BEGIN IMMEDIATE (FR-3.2).
            WalletRecord source = walletRepository.findById(conn, sourceWalletId);
            if (source == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND", "Source wallet not found.");
            }
            if (source.balanceMinor() < amountMinor) {
                throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS",
                        "The source wallet does not have sufficient funds.");
            }

            String sourceAccountId = ledgerRepository.findWalletAccountId(conn, sourceWalletId);
            String destAccountId = ledgerRepository.findWalletAccountId(conn, destinationWalletId);
            if (sourceAccountId == null || destAccountId == null) {
                throw new IllegalStateException("Required wallet ledger accounts missing.");
            }

            String transferId = UUID.randomUUID().toString();
            String txId = UUID.randomUUID().toString();
            String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

            TransferRecord transfer = new TransferRecord(
                    transferId, sourceWalletId, destinationWalletId,
                    currencyCode, amountMinor, "COMPLETED", now);

            transferRepository.insert(conn, transfer);
            ledgerRepository.insertTransaction(conn, txId, "TRANSFER", transferId, currencyCode, now);

            List<LedgerEntryDraft> drafts = List.of(
                    new LedgerEntryDraft(UUID.randomUUID().toString(), sourceAccountId, currencyCode, amountMinor, 0L),
                    new LedgerEntryDraft(UUID.randomUUID().toString(), destAccountId, currencyCode, 0L, amountMinor)
            );
            ledgerRepository.appendBalancedEntriesAndUpdateCheckpoint(
                    conn, txId, currencyCode, now, drafts, hashChainService);

            walletRepository.addBalance(conn, sourceWalletId, -amountMinor);
            walletRepository.addBalance(conn, destinationWalletId, amountMinor);

            idempotencyRepository.insert(conn, new IdempotencyRecord(
                    authenticatedUserId, idempotencyKey, fingerprint, transferId, now));

            return new TransferExecutionResult(transfer, false);
        });
    }

    public TransferRecord getTransfer(String authenticatedUserId, String transferId) {
        WalletService.validateUuid(transferId, "transferId");
        return txExecutor.executeRead(conn -> {
            TransferRecord transfer = transferRepository.findById(conn, transferId);
            if (transfer == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "Transfer not found.");
            }
            WalletRecord source = walletRepository.findById(conn, transfer.sourceWalletId());
            WalletRecord destination = walletRepository.findById(conn, transfer.destinationWalletId());
            boolean ownsSource = source != null && authenticatedUserId.equals(source.userId());
            boolean ownsDest = destination != null && authenticatedUserId.equals(destination.userId());
            if (!ownsSource && !ownsDest) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "You do not have access to this transfer.");
            }
            return transfer;
        });
    }

    public TransferRecord reverseTransfer(String authenticatedUserId, String transferId) {
        WalletService.validateUuid(transferId, "transferId");

        // Pre-lock existence, ownership, and basic eligibility checks.
        txExecutor.executeRead(conn -> {
            TransferRecord original = transferRepository.findById(conn, transferId);
            if (original == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "Transfer not found.");
            }
            WalletRecord originalSource = walletRepository.findById(conn, original.sourceWalletId());
            if (originalSource == null || !authenticatedUserId.equals(originalSource.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "Only the owner of the original transfer may reverse it.");
            }
            if (original.reversalOf() != null) {
                throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_NOT_REVERSIBLE",
                        "A reversal transfer cannot be reversed.");
            }
            if (!"COMPLETED".equals(original.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_NOT_REVERSIBLE",
                        "Transfer has already been reversed.");
            }
            return null;
        });

        return txExecutor.executeWrite(conn -> {
            // Re-read inside BEGIN IMMEDIATE to prevent concurrent duplicate reversals.
            TransferRecord original = transferRepository.findById(conn, transferId);
            if (original == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "TRANSFER_NOT_FOUND",
                        "Transfer not found.");
            }
            if (original.reversalOf() != null) {
                throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_NOT_REVERSIBLE",
                        "A reversal transfer cannot be reversed.");
            }
            if (!"COMPLETED".equals(original.status())) {
                throw new ApiException(HttpStatus.CONFLICT, "TRANSFER_NOT_REVERSIBLE",
                        "Transfer has already been reversed.");
            }

            // Authoritative balance check on the original destination wallet under write lock.
            WalletRecord reversalSource = walletRepository.findById(conn, original.destinationWalletId());
            if (reversalSource == null) {
                throw new IllegalStateException("Destination wallet missing for reversal.");
            }
            if (reversalSource.balanceMinor() < original.amountMinor()) {
                throw new ApiException(HttpStatus.CONFLICT, "INSUFFICIENT_FUNDS",
                        "Destination wallet does not have sufficient funds to reverse this transfer.");
            }

            String reversalSourceAccountId = ledgerRepository.findWalletAccountId(conn, original.destinationWalletId());
            String reversalDestAccountId = ledgerRepository.findWalletAccountId(conn, original.sourceWalletId());
            if (reversalSourceAccountId == null || reversalDestAccountId == null) {
                throw new IllegalStateException("Required wallet ledger accounts missing.");
            }

            String reversalTransferId = UUID.randomUUID().toString();
            String txId = UUID.randomUUID().toString();
            String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
            String currency = original.currencyCode();
            long amount = original.amountMinor();

            TransferRecord reversal = new TransferRecord(
                    reversalTransferId,
                    original.destinationWalletId(),
                    original.sourceWalletId(),
                    currency,
                    amount,
                    "COMPLETED",
                    original.transferId(),
                    now);

            transferRepository.insert(conn, reversal);
            ledgerRepository.insertTransaction(conn, txId, "REVERSAL", reversalTransferId, currency, now);

            List<LedgerEntryDraft> drafts = List.of(
                    new LedgerEntryDraft(UUID.randomUUID().toString(), reversalSourceAccountId, currency, amount, 0L),
                    new LedgerEntryDraft(UUID.randomUUID().toString(), reversalDestAccountId, currency, 0L, amount)
            );
            ledgerRepository.appendBalancedEntriesAndUpdateCheckpoint(
                    conn, txId, currency, now, drafts, hashChainService);

            walletRepository.addBalance(conn, original.destinationWalletId(), -amount);
            walletRepository.addBalance(conn, original.sourceWalletId(), amount);
            transferRepository.markReversed(conn, original.transferId());

            return reversal;
        });
    }

    private static void validateIdempotencyKey(String key) {
        if (key == null || key.isEmpty() || key.length() > 64) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "Idempotency-Key header must be 1 to 64 ASCII characters.");
        }
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (c < 0x20 || c > 0x7E) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                        "Idempotency-Key header must contain printable ASCII characters only.");
            }
        }
    }

    public static byte[] computeFingerprint(
            String userId,
            String sourceWalletId,
            String destinationWalletId,
            String currencyCode,
            long amountMinor) {
        try {
            ByteArrayOutputStream buf = new ByteArrayOutputStream();
            DataOutputStream out = new DataOutputStream(buf);
            writeString(out, userId);
            writeString(out, sourceWalletId);
            writeString(out, destinationWalletId);
            writeString(out, currencyCode);
            out.writeLong(amountMinor);
            out.flush();
            return MessageDigest.getInstance("SHA-256").digest(buf.toByteArray());
        } catch (IOException | GeneralSecurityException ex) {
            throw new IllegalStateException("Failed to compute request fingerprint.", ex);
        }
    }

    private static void writeString(DataOutputStream out, String value) throws IOException {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        out.writeInt(bytes.length);
        out.write(bytes);
    }
}
