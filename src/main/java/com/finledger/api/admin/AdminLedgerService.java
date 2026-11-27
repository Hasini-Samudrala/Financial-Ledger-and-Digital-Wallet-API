package com.finledger.api.admin;

import com.finledger.api.db.SqliteTransactionExecutor;
import com.finledger.api.ledger.LedgerHashChainService;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.List;

/**
 * Read-only admin operations for ledger hash-chain integrity verification
 * ({@code GET /admin/ledger-integrity}) and double-entry reconciliation
 * ({@code GET /admin/reconciliation}).
 */
@Service
public class AdminLedgerService {

    public record LedgerIntegrityReport(
            String status,
            long totalEntries,
            long lastSequence,
            boolean chainValid,
            boolean checkpointValid,
            Long earliestMismatchSequence,
            String details
    ) {
    }

    public record CurrencyTotal(
            String currencyCode,
            long totalDebitsMinor,
            long totalCreditsMinor,
            boolean balanced
    ) {
    }

    public record UnbalancedTransaction(
            String transactionId,
            String currencyCode,
            long totalDebitsMinor,
            long totalCreditsMinor
    ) {
    }

    public record WalletDiscrepancy(
            String walletId,
            String userId,
            String currencyCode,
            long storedBalanceMinor,
            long ledgerBalanceMinor,
            long differenceMinor
    ) {
    }

    public record ReconciliationReport(
            String status,
            List<CurrencyTotal> currencyTotals,
            List<UnbalancedTransaction> unbalancedTransactions,
            List<WalletDiscrepancy> walletDiscrepancies,
            int walletCount,
            int matchedWalletCount,
            String checkedAt
    ) {
    }

    private final SqliteTransactionExecutor txExecutor;
    private final LedgerHashChainService hashChainService;

    public AdminLedgerService(
            SqliteTransactionExecutor txExecutor,
            LedgerHashChainService hashChainService) {
        this.txExecutor = txExecutor;
        this.hashChainService = hashChainService;
    }

    public LedgerIntegrityReport verifyIntegrity() {
        return txExecutor.executeReadTransaction(conn -> {
            String sql = """
                    SELECT entry_id, sequence_no, transaction_id, account_id, currency_code,
                           debit_minor, credit_minor, previous_hash, entry_hash, created_at
                    FROM ledger_entries
                    ORDER BY sequence_no ASC
                    """;

            long totalEntries = 0;
            long expectedSequence = 1;
            long actualLastSequence = 0;
            byte[] expectedPrevHash = LedgerHashChainService.GENESIS_HASH;
            byte[] actualHeadHash = LedgerHashChainService.GENESIS_HASH;

            boolean chainValid = true;
            Long earliestMismatchSequence = null;
            StringBuilder reason = new StringBuilder();

            try (PreparedStatement ps = conn.prepareStatement(sql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    totalEntries++;
                    long seq = rs.getLong("sequence_no");
                    String entryId = rs.getString("entry_id");
                    String txId = rs.getString("transaction_id");
                    String accountId = rs.getString("account_id");
                    String currency = rs.getString("currency_code");
                    long debit = rs.getLong("debit_minor");
                    long credit = rs.getLong("credit_minor");
                    byte[] prevHash = rs.getBytes("previous_hash");
                    byte[] storedHash = rs.getBytes("entry_hash");
                    String createdAt = rs.getString("created_at");

                    if (chainValid) {
                        if (seq != expectedSequence) {
                            chainValid = false;
                            earliestMismatchSequence = expectedSequence;
                            reason.append("Missing or out-of-order sequence at expected ")
                                    .append(expectedSequence).append(" (found ").append(seq).append(").");
                        } else if (!MessageDigest.isEqual(prevHash, expectedPrevHash)) {
                            chainValid = false;
                            earliestMismatchSequence = seq;
                            reason.append("Previous hash mismatch at sequence ").append(seq).append(".");
                        } else {
                            byte[] canonical = hashChainService.canonicalEncode(
                                    seq, entryId, txId, accountId, currency, debit, credit, createdAt);
                            byte[] recomputed = hashChainService.computeEntryHash(expectedPrevHash, canonical);
                            if (!MessageDigest.isEqual(storedHash, recomputed)) {
                                chainValid = false;
                                earliestMismatchSequence = seq;
                                reason.append("Entry hash mismatch at sequence ").append(seq).append(".");
                            }
                        }
                    }

                    actualLastSequence = seq;
                    actualHeadHash = storedHash;
                    expectedPrevHash = storedHash;
                    expectedSequence = seq + 1;
                }
            }

            boolean checkpointValid = verifyCheckpoint(conn, actualLastSequence, actualHeadHash, reason);
            String status = (chainValid && checkpointValid) ? "PASS" : "FAIL";
            String details = "PASS".equals(status) ? "OK" : reason.toString().trim();

            return new LedgerIntegrityReport(
                    status,
                    totalEntries,
                    actualLastSequence,
                    chainValid,
                    checkpointValid,
                    earliestMismatchSequence,
                    details);
        });
    }

    private boolean verifyCheckpoint(
            Connection conn,
            long actualLastSequence,
            byte[] actualHeadHash,
            StringBuilder reason) throws SQLException {
        String cpSql = """
                SELECT last_entry_sequence, chain_head_hash, checkpoint_mac
                FROM integrity_checkpoints
                WHERE checkpoint_id = 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(cpSql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                reason.append(" Integrity checkpoint row is missing.");
                return false;
            }
            long cpSeq = rs.getLong("last_entry_sequence");
            byte[] cpHead = rs.getBytes("chain_head_hash");
            byte[] cpMac = rs.getBytes("checkpoint_mac");

            if (cpSeq != actualLastSequence) {
                reason.append(" Checkpoint sequence (").append(cpSeq)
                        .append(") does not match ledger last sequence (").append(actualLastSequence).append(").");
                return false;
            }
            if (!MessageDigest.isEqual(cpHead, actualHeadHash)) {
                reason.append(" Checkpoint chain head hash does not match ledger head.");
                return false;
            }
            byte[] expectedMac = hashChainService.computeCheckpointMac(cpSeq, cpHead);
            if (!MessageDigest.isEqual(cpMac, expectedMac)) {
                reason.append(" Checkpoint HMAC verification failed.");
                return false;
            }
            return true;
        }
    }

    public ReconciliationReport reconcile() {
        return txExecutor.executeReadTransaction(conn -> {
            List<CurrencyTotal> currencyTotals = readCurrencyTotals(conn);
            List<UnbalancedTransaction> unbalancedTxs = readUnbalancedTransactions(conn);

            List<WalletDiscrepancy> discrepancies = new ArrayList<>();
            int walletCount = 0;
            int matchedCount = 0;

            String walletSql = """
                    SELECT w.wallet_id,
                           w.user_id,
                           w.currency_code,
                           w.stored_balance_minor,
                           COALESCE(SUM(le.credit_minor - le.debit_minor), 0) AS ledger_balance_minor
                    FROM wallets w
                    LEFT JOIN ledger_accounts la ON la.wallet_id = w.wallet_id
                    LEFT JOIN ledger_entries le ON le.account_id = la.account_id
                    GROUP BY w.wallet_id, w.user_id, w.currency_code, w.stored_balance_minor
                    ORDER BY w.wallet_id ASC
                    """;
            try (PreparedStatement ps = conn.prepareStatement(walletSql);
                 ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    walletCount++;
                    long stored = rs.getLong("stored_balance_minor");
                    long derived = rs.getLong("ledger_balance_minor");
                    long diff = stored - derived;
                    if (diff == 0) {
                        matchedCount++;
                    } else {
                        discrepancies.add(new WalletDiscrepancy(
                                rs.getString("wallet_id"),
                                rs.getString("user_id"),
                                rs.getString("currency_code"),
                                stored,
                                derived,
                                diff));
                    }
                }
            }

            boolean allCurrenciesBalanced = currencyTotals.stream().allMatch(CurrencyTotal::balanced);
            boolean pass = allCurrenciesBalanced && unbalancedTxs.isEmpty() && discrepancies.isEmpty();

            return new ReconciliationReport(
                    pass ? "PASS" : "FAIL",
                    currencyTotals,
                    unbalancedTxs,
                    discrepancies,
                    walletCount,
                    matchedCount,
                    Instant.now().truncatedTo(ChronoUnit.SECONDS).toString());
        });
    }

    private static List<CurrencyTotal> readCurrencyTotals(Connection conn) throws SQLException {
        String sql = """
                SELECT currency_code,
                       COALESCE(SUM(debit_minor), 0) AS total_debits,
                       COALESCE(SUM(credit_minor), 0) AS total_credits
                FROM ledger_entries
                GROUP BY currency_code
                ORDER BY currency_code ASC
                """;
        List<CurrencyTotal> totals = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                long debits = rs.getLong("total_debits");
                long credits = rs.getLong("total_credits");
                totals.add(new CurrencyTotal(
                        rs.getString("currency_code"),
                        debits,
                        credits,
                        debits == credits));
            }
        }
        return totals;
    }

    private static List<UnbalancedTransaction> readUnbalancedTransactions(Connection conn) throws SQLException {
        String sql = """
                SELECT transaction_id,
                       currency_code,
                       SUM(debit_minor) AS total_debits,
                       SUM(credit_minor) AS total_credits
                FROM ledger_entries
                GROUP BY transaction_id, currency_code
                HAVING SUM(debit_minor) <> SUM(credit_minor)
                ORDER BY transaction_id ASC
                """;
        List<UnbalancedTransaction> list = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            while (rs.next()) {
                list.add(new UnbalancedTransaction(
                        rs.getString("transaction_id"),
                        rs.getString("currency_code"),
                        rs.getLong("total_debits"),
                        rs.getLong("total_credits")));
            }
        }
        return list;
    }
}
