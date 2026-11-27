package com.finledger.api;

import com.finledger.api.config.DatabaseInitializer;
import com.finledger.api.db.SqliteTransactionExecutor;
import com.finledger.api.ledger.LedgerEntryDraft;
import com.finledger.api.ledger.LedgerHashChainService;
import com.finledger.api.ledger.LedgerRepository;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest
class DatabaseAndLedgerCoreTest {

    @Autowired
    private DataSource dataSource;

    @Autowired
    private DatabaseInitializer databaseInitializer;

    @Autowired
    private SqliteTransactionExecutor txExecutor;

    @Autowired
    private LedgerRepository ledgerRepository;

    @Autowired
    private LedgerHashChainService hashChainService;

    @Test
    void schemaAndPragmasInitializedCorrectly() throws Exception {
        try (Connection conn = dataSource.getConnection();
             Statement stmt = conn.createStatement()) {
            try (ResultSet rs = stmt.executeQuery("PRAGMA journal_mode;")) {
                assertTrue(rs.next());
                assertEquals("wal", rs.getString(1).toLowerCase());
            }
            try (ResultSet rs = stmt.executeQuery("PRAGMA foreign_keys;")) {
                assertTrue(rs.next());
                assertEquals(1, rs.getInt(1));
            }
            try (ResultSet rs = stmt.executeQuery("PRAGMA synchronous;")) {
                assertTrue(rs.next());
                assertEquals(2, rs.getInt(1)); // 2 = FULL
            }
            try (ResultSet rs = stmt.executeQuery("PRAGMA busy_timeout;")) {
                assertTrue(rs.next());
                assertEquals(5000, rs.getInt(1));
            }
        }

        txExecutor.executeRead(conn -> {
            assertNotNull(ledgerRepository.findSystemFundingAccountId(conn, "INR"));
            assertNotNull(ledgerRepository.findSystemFundingAccountId(conn, "USD"));
            return null;
        });
    }

    @Test
    void databaseInitializationIsIdempotent() throws Exception {
        databaseInitializer.initialize();
        databaseInitializer.initialize();

        int systemAccountCount = txExecutor.executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT COUNT(*) FROM ledger_accounts WHERE account_type = 'SYSTEM_FUNDING'");
                 ResultSet rs = ps.executeQuery()) {
                rs.next();
                return rs.getInt(1);
            }
        });
        assertEquals(2, systemAccountCount);
    }

    @Test
    void writeRollbackLeavesNoPartialChanges() {
        long beforeTxCount = countRows("ledger_transactions");
        long beforeEntryCount = countRows("ledger_entries");
        String txId = UUID.randomUUID().toString();

        assertThrows(RuntimeException.class, () -> txExecutor.executeWrite(conn -> {
            String sysAcc = ledgerRepository.findSystemFundingAccountId(conn, "INR");
            ledgerRepository.insertTransaction(conn, txId, "FUNDING", UUID.randomUUID().toString(), "INR",
                    "2026-11-26T00:00:00Z");
            ledgerRepository.appendBalancedEntriesAndUpdateCheckpoint(
                    conn, txId, "INR", "2026-11-26T00:00:00Z",
                    List.of(
                            new LedgerEntryDraft(UUID.randomUUID().toString(), sysAcc, "INR", 100L, 0L),
                            new LedgerEntryDraft(UUID.randomUUID().toString(), sysAcc, "INR", 0L, 100L)
                    ),
                    hashChainService);
            throw new RuntimeException("Simulated failure after writes");
        }));

        assertEquals(beforeTxCount, countRows("ledger_transactions"));
        assertEquals(beforeEntryCount, countRows("ledger_entries"));
    }

    @Test
    void immutabilityTriggersRejectUpdateAndDeleteOnLedgerTables() {
        String txId = UUID.randomUUID().toString();
        String entry1 = UUID.randomUUID().toString();
        String entry2 = UUID.randomUUID().toString();

        txExecutor.executeWrite(conn -> {
            String sysAcc = ledgerRepository.findSystemFundingAccountId(conn, "USD");
            ledgerRepository.insertTransaction(conn, txId, "FUNDING", UUID.randomUUID().toString(), "USD",
                    "2026-11-26T00:00:00Z");
            ledgerRepository.appendBalancedEntriesAndUpdateCheckpoint(
                    conn, txId, "USD", "2026-11-26T00:00:00Z",
                    List.of(
                            new LedgerEntryDraft(entry1, sysAcc, "USD", 500L, 0L),
                            new LedgerEntryDraft(entry2, sysAcc, "USD", 0L, 500L)
                    ),
                    hashChainService);
            return null;
        });

        assertThrows(SQLException.class, () -> executeSql("UPDATE ledger_entries SET debit_minor = 999 WHERE entry_id = ?", entry1));
        assertThrows(SQLException.class, () -> executeSql("DELETE FROM ledger_entries WHERE entry_id = ?", entry1));
        assertThrows(SQLException.class, () -> executeSql("UPDATE ledger_transactions SET currency_code = 'INR' WHERE ledger_transaction_id = ?", txId));
        assertThrows(SQLException.class, () -> executeSql("DELETE FROM ledger_transactions WHERE ledger_transaction_id = ?", txId));
    }

    @Test
    void canonicalHashChainAndCheckpointAreDeterministic() {
        byte[] canonical = hashChainService.canonicalEncode(
                1L, "e1", "t1", "a1", "INR", 1000L, 0L, "2026-11-26T00:00:00Z");
        byte[] hash1 = hashChainService.computeEntryHash(LedgerHashChainService.GENESIS_HASH, canonical);
        byte[] hash2 = hashChainService.computeEntryHash(LedgerHashChainService.GENESIS_HASH, canonical);
        assertEquals(32, hash1.length);
        assertArrayEquals(hash1, hash2);

        byte[] mac1 = hashChainService.computeCheckpointMac(1L, hash1);
        byte[] mac2 = hashChainService.computeCheckpointMac(1L, hash1);
        assertEquals(32, mac1.length);
        assertArrayEquals(mac1, mac2);
    }

    private long countRows(String table) {
        return txExecutor.executeRead(conn -> {
            try (Statement stmt = conn.createStatement();
                 ResultSet rs = stmt.executeQuery("SELECT COUNT(*) FROM " + table)) {
                rs.next();
                return rs.getLong(1);
            }
        });
    }

    private void executeSql(String sql, String param) throws SQLException {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            ps.executeUpdate();
        }
    }
}
