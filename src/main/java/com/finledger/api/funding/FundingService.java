package com.finledger.api.funding;

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

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

/**
 * Executes atomic admin wallet funding with double-entry ledger records,
 * hash-chain updates, and checkpoint updates inside {@code BEGIN IMMEDIATE}.
 */
@Service
public class FundingService {

    public record FundingResult(String walletId, long balanceMinor, String currencyCode) {
    }

    private final WalletRepository walletRepository;
    private final LedgerRepository ledgerRepository;
    private final LedgerHashChainService hashChainService;
    private final SupportedCurrencies supportedCurrencies;
    private final SqliteTransactionExecutor txExecutor;

    public FundingService(
            WalletRepository walletRepository,
            LedgerRepository ledgerRepository,
            LedgerHashChainService hashChainService,
            SupportedCurrencies supportedCurrencies,
            SqliteTransactionExecutor txExecutor) {
        this.walletRepository = walletRepository;
        this.ledgerRepository = ledgerRepository;
        this.hashChainService = hashChainService;
        this.supportedCurrencies = supportedCurrencies;
        this.txExecutor = txExecutor;
    }

    public FundingResult fundWallet(String walletId, Long amountMinor, String rawCurrencyCode) {
        WalletService.validateUuid(walletId, "walletId");
        if (amountMinor == null || amountMinor <= 0) {
            throw new ApiException(HttpStatus.BAD_REQUEST, "INVALID_AMOUNT",
                    "amountMinor must be a positive integer.");
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

        return txExecutor.executeWrite(conn -> {
            WalletRecord wallet = walletRepository.findById(conn, walletId);
            if (wallet == null) {
                throw new ApiException(HttpStatus.NOT_FOUND, "WALLET_NOT_FOUND",
                        "Wallet not found.");
            }
            if (!wallet.currencyCode().equals(currencyCode)) {
                throw new ApiException(HttpStatus.BAD_REQUEST, "CURRENCY_MISMATCH",
                        "Funding currency does not match wallet currency.");
            }

            String systemAccountId = ledgerRepository.findSystemFundingAccountId(conn, currencyCode);
            String walletAccountId = ledgerRepository.findWalletAccountId(conn, walletId);
            if (systemAccountId == null || walletAccountId == null) {
                throw new IllegalStateException("Required ledger accounts missing for funding.");
            }

            String txId = UUID.randomUUID().toString();
            String referenceId = UUID.randomUUID().toString();
            String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();

            ledgerRepository.insertTransaction(conn, txId, "FUNDING", referenceId, currencyCode, now);
            List<LedgerEntryDraft> drafts = List.of(
                    new LedgerEntryDraft(UUID.randomUUID().toString(), systemAccountId, currencyCode, amountMinor, 0L),
                    new LedgerEntryDraft(UUID.randomUUID().toString(), walletAccountId, currencyCode, 0L, amountMinor)
            );
            ledgerRepository.appendBalancedEntriesAndUpdateCheckpoint(
                    conn, txId, currencyCode, now, drafts, hashChainService);

            walletRepository.addBalance(conn, walletId, amountMinor);
            WalletRecord updated = walletRepository.findById(conn, walletId);
            return new FundingResult(updated.walletId(), updated.balanceMinor(), updated.currencyCode());
        });
    }
}
