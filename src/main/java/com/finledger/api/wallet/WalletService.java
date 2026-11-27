package com.finledger.api.wallet;

import com.finledger.api.config.SupportedCurrencies;
import com.finledger.api.db.SqliteTransactionExecutor;
import com.finledger.api.exception.ApiException;
import com.finledger.api.ledger.LedgerRepository;
import com.finledger.api.user.UserService;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.UUID;

/**
 * Implements wallet creation and retrieval with username resolution,
 * ownership enforcement, and currency validation.
 */
@Service
public class WalletService {

    private final WalletRepository walletRepository;
    private final LedgerRepository ledgerRepository;
    private final UserService userService;
    private final SupportedCurrencies supportedCurrencies;
    private final SqliteTransactionExecutor txExecutor;

    public WalletService(
            WalletRepository walletRepository,
            LedgerRepository ledgerRepository,
            UserService userService,
            SupportedCurrencies supportedCurrencies,
            SqliteTransactionExecutor txExecutor) {
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.userService = userService;
        this.supportedCurrencies = supportedCurrencies;
        this.txExecutor = txExecutor;
    }

    public WalletRecord createWalletByUsername(
            String authenticatedUserId, String username, String rawCurrencyCode) {
        String targetUserId = userService.resolveUserIdByUsername(username);
        if (!authenticatedUserId.equals(targetUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "You may only create wallets for your own user account.");
        }
        String currencyCode = validateAndNormalizeCurrency(rawCurrencyCode);

        String walletId = UUID.randomUUID().toString();
        String accountId = UUID.randomUUID().toString();
        WalletRecord wallet = new WalletRecord(walletId, targetUserId, currencyCode, 0L);

        return txExecutor.executeWrite(conn -> {
            if (walletRepository.existsByUserAndCurrency(conn, targetUserId, currencyCode)) {
                throw new ApiException(HttpStatus.CONFLICT, "DUPLICATE_WALLET",
                        "User already has a wallet in " + currencyCode + ".");
            }
            walletRepository.insert(conn, wallet);
            ledgerRepository.createWalletAccount(conn, accountId, walletId, currencyCode);
            return wallet;
        });
    }

    public WalletRecord getWalletByUsernameAndCurrency(
            String authenticatedUserId, String username, String rawCurrencyCode) {
        String targetUserId = userService.resolveUserIdByUsername(username);
        if (!authenticatedUserId.equals(targetUserId)) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "You do not have access to this user's wallets.");
        }
        String currencyCode = validateAndNormalizeCurrency(rawCurrencyCode);

        WalletRecord wallet = txExecutor.executeRead(
                conn -> walletRepository.findByUserAndCurrency(conn, targetUserId, currencyCode));
        if (wallet == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND",
                    "Wallet not found for currency " + currencyCode + ".");
        }
        return wallet;
    }

    public record LedgerHistoryResponse(
            String walletId,
            List<LedgerRepository.LedgerEntryView> entries,
            Long nextCursor
    ) {
    }

    public LedgerHistoryResponse getLedgerHistory(
            String authenticatedUserId, String walletId, Long cursor, Integer limit) {
        validateUuid(walletId, "walletId");
        long effectiveCursor = (cursor == null) ? 0L : cursor;
        if (effectiveCursor < 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "cursor must be a non-negative integer.");
        }
        int rawLimit = (limit == null) ? 20 : limit;
        if (rawLimit <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    "limit must be a positive integer.");
        }
        int effectiveLimit = Math.min(rawLimit, 100);

        return txExecutor.executeRead(conn -> {
            WalletRecord wallet = walletRepository.findById(conn, walletId);
            if (wallet == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND",
                        "Wallet not found.");
            }
            if (!authenticatedUserId.equals(wallet.userId())) {
                throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                        "You do not have access to this wallet.");
            }
            String accountId = ledgerRepository.findWalletAccountId(conn, walletId);
            if (accountId == null) {
                throw new IllegalStateException("Wallet ledger account missing.");
            }

            List<LedgerRepository.LedgerEntryView> fetched = ledgerRepository.findWalletEntriesAfterSequence(
                    conn, accountId, effectiveCursor, effectiveLimit + 1);
            boolean hasMore = fetched.size() > effectiveLimit;
            List<LedgerRepository.LedgerEntryView> page = hasMore
                    ? fetched.subList(0, effectiveLimit)
                    : fetched;
            Long nextCursor = hasMore ? page.get(page.size() - 1).sequenceNo() : null;
            return new LedgerHistoryResponse(walletId, page, nextCursor);
        });
    }

    public WalletRecord getWallet(String authenticatedUserId, String walletId) {
        validateUuid(walletId, "walletId");
        WalletRecord wallet = txExecutor.executeRead(conn -> walletRepository.findById(conn, walletId));
        if (wallet == null) {
            throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND",
                    "Wallet not found.");
        }
        if (!authenticatedUserId.equals(wallet.userId())) {
            throw new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN",
                    "You do not have access to this wallet.");
        }
        return wallet;
    }

    private String validateAndNormalizeCurrency(String rawCurrencyCode) {
        if (rawCurrencyCode == null || rawCurrencyCode.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY",
                    "Currency code is required.");
        }
        String currencyCode = rawCurrencyCode.trim().toUpperCase();
        if (!supportedCurrencies.isSupported(currencyCode)) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_CURRENCY",
                    "Currency code '" + rawCurrencyCode + "' is not supported.");
        }
        return currencyCode;
    }

    public static void validateUuid(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    fieldName + " must be a valid UUID.");
        }
        try {
            UUID.fromString(value);
        } catch (IllegalArgumentException ex) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "VALIDATION_ERROR",
                    fieldName + " must be a valid UUID.");
        }
    }
}
