package com.finledger.api.ledger;

import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

/**
 * Parameterised JDBC repository for ledger accounts, transactions, entries,
 * and the singleton integrity checkpoint.
 */
@Repository
public class LedgerRepository {

    public record CheckpointState(long lastEntrySequence, byte[] chainHeadHash) {
    }

    /**
     * Public API projection of a ledger entry (hash fields intentionally omitted per ADR-006).
     */
    public record LedgerEntryView(
            String entryId,
            long sequenceNo,
            String transactionId,
            String currencyCode,
            long debitMinor,
            long creditMinor,
            String createdAt
    ) {
    }

    public List<LedgerEntryView> findWalletEntriesAfterSequence(
            Connection conn, String accountId, long afterSequence, int limitPlusOne) throws SQLException {
        String sql = """
                SELECT entry_id, sequence_no, transaction_id, currency_code,
                       debit_minor, credit_minor, created_at
                FROM ledger_entries
                WHERE account_id = ? AND sequence_no > ?
                ORDER BY sequence_no ASC
                LIMIT ?
                """;
        List<LedgerEntryView> rows = new ArrayList<>();
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountId);
            ps.setLong(2, afterSequence);
            ps.setInt(3, limitPlusOne);
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    rows.add(new LedgerEntryView(
                            rs.getString("entry_id"),
                            rs.getLong("sequence_no"),
                            rs.getString("transaction_id"),
                            rs.getString("currency_code"),
                            rs.getLong("debit_minor"),
                            rs.getLong("credit_minor"),
                            rs.getString("created_at")));
                }
            }
        }
        return rows;
    }

    public void createWalletAccount(Connection conn, String accountId, String walletId, String currencyCode)
            throws SQLException {
        String sql = """
                INSERT INTO ledger_accounts (account_id, account_type, wallet_id, currency_code)
                VALUES (?, 'WALLET', ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, accountId);
            ps.setString(2, walletId);
            ps.setString(3, currencyCode);
            ps.executeUpdate();
        }
    }

    public String findWalletAccountId(Connection conn, String walletId) throws SQLException {
        String sql = "SELECT account_id FROM ledger_accounts WHERE wallet_id = ? AND account_type = 'WALLET'";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("account_id") : null;
            }
        }
    }

    public String findSystemFundingAccountId(Connection conn, String currencyCode) throws SQLException {
        String sql = """
                SELECT account_id
                FROM ledger_accounts
                WHERE currency_code = ? AND account_type = 'SYSTEM_FUNDING'
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, currencyCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString("account_id") : null;
            }
        }
    }

    public void insertTransaction(
            Connection conn,
            String transactionId,
            String type,
            String referenceId,
            String currencyCode,
            String createdAt) throws SQLException {
        String sql = """
                INSERT INTO ledger_transactions
                    (ledger_transaction_id, type, reference_id, currency_code, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, transactionId);
            ps.setString(2, type);
            ps.setString(3, referenceId);
            ps.setString(4, currencyCode);
            ps.setString(5, createdAt);
            ps.executeUpdate();
        }
    }

    /**
     * Validates double-entry balance, appends chained ledger entries, and
     * updates {@code integrity_checkpoints} atomically within the caller's
     * {@code BEGIN IMMEDIATE} transaction.
     */
    public void appendBalancedEntriesAndUpdateCheckpoint(
            Connection conn,
            String transactionId,
            String currencyCode,
            String createdAt,
            List<LedgerEntryDraft> drafts,
            LedgerHashChainService hashChainService) throws SQLException {
        validateBalanced(currencyCode, drafts);

        CheckpointState current = readCheckpoint(conn);
        long sequence = current.lastEntrySequence();
        byte[] previousHash = current.chainHeadHash();

        String insertEntrySql = """
                INSERT INTO ledger_entries
                    (entry_id, sequence_no, transaction_id, account_id, currency_code,
                     debit_minor, credit_minor, previous_hash, entry_hash, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """;

        try (PreparedStatement ps = conn.prepareStatement(insertEntrySql)) {
            for (LedgerEntryDraft draft : drafts) {
                sequence++;
                byte[] canonical = hashChainService.canonicalEncode(
                        sequence,
                        draft.entryId(),
                        transactionId,
                        draft.accountId(),
                        draft.currencyCode(),
                        draft.debitMinor(),
                        draft.creditMinor(),
                        createdAt);
                byte[] entryHash = hashChainService.computeEntryHash(previousHash, canonical);

                ps.setString(1, draft.entryId());
                ps.setLong(2, sequence);
                ps.setString(3, transactionId);
                ps.setString(4, draft.accountId());
                ps.setString(5, draft.currencyCode());
                ps.setLong(6, draft.debitMinor());
                ps.setLong(7, draft.creditMinor());
                ps.setBytes(8, previousHash);
                ps.setBytes(9, entryHash);
                ps.setString(10, createdAt);
                ps.executeUpdate();

                previousHash = entryHash;
            }
        }

        byte[] newMac = hashChainService.computeCheckpointMac(sequence, previousHash);
        String updateCheckpointSql = """
                UPDATE integrity_checkpoints
                SET last_entry_sequence = ?,
                    chain_head_hash     = ?,
                    checkpoint_mac      = ?,
                    updated_at          = ?
                WHERE checkpoint_id = 1
                """;
        try (PreparedStatement ps = conn.prepareStatement(updateCheckpointSql)) {
            ps.setLong(1, sequence);
            ps.setBytes(2, previousHash);
            ps.setBytes(3, newMac);
            ps.setString(4, createdAt);
            int updated = ps.executeUpdate();
            if (updated != 1) {
                throw new IllegalStateException("Singleton integrity_checkpoints row missing.");
            }
        }
    }

    private CheckpointState readCheckpoint(Connection conn) throws SQLException {
        String sql = "SELECT last_entry_sequence, chain_head_hash FROM integrity_checkpoints WHERE checkpoint_id = 1";
        try (PreparedStatement ps = conn.prepareStatement(sql);
             ResultSet rs = ps.executeQuery()) {
            if (!rs.next()) {
                throw new IllegalStateException("Singleton integrity_checkpoints row not initialized.");
            }
            return new CheckpointState(
                    rs.getLong("last_entry_sequence"),
                    rs.getBytes("chain_head_hash"));
        }
    }

    public static void validateBalanced(String currencyCode, List<LedgerEntryDraft> drafts) {
        if (drafts == null || drafts.isEmpty()) {
            throw new IllegalArgumentException("Ledger transaction must have at least one entry.");
        }
        long totalDebit = 0;
        long totalCredit = 0;
        for (LedgerEntryDraft draft : drafts) {
            if (!currencyCode.equals(draft.currencyCode())) {
                throw new IllegalArgumentException("All entries in a transaction must share one currency.");
            }
            boolean validSides = (draft.debitMinor() > 0 && draft.creditMinor() == 0)
                    || (draft.debitMinor() == 0 && draft.creditMinor() > 0);
            if (!validSides) {
                throw new IllegalArgumentException("Each entry must have exactly one positive side.");
            }
            totalDebit = Math.addExact(totalDebit, draft.debitMinor());
            totalCredit = Math.addExact(totalCredit, draft.creditMinor());
        }
        if (totalDebit != totalCredit || totalDebit <= 0) {
            throw new IllegalArgumentException("Ledger transaction debits and credits must balance.");
        }
    }
}
