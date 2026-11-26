package com.finledger.api.config;

import com.finledger.api.ledger.LedgerHashChainService;
import jakarta.annotation.PostConstruct;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.time.temporal.ChronoUnit;

/**
 * Idempotently initializes WAL mode, database schema, system funding accounts,
 * and the singleton genesis integrity checkpoint on startup.
 */
@Component
public class DatabaseInitializer {

    private final DataSource dataSource;
    private final SupportedCurrencies supportedCurrencies;
    private final LedgerHashChainService hashChainService;

    public DatabaseInitializer(
            DataSource dataSource,
            SupportedCurrencies supportedCurrencies,
            LedgerHashChainService hashChainService) {
        this.dataSource = dataSource;
        this.supportedCurrencies = supportedCurrencies;
        this.hashChainService = hashChainService;
    }

    @PostConstruct
    public void initialize() throws SQLException, IOException {
        String schemaSql = loadSchemaSql();
        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("PRAGMA journal_mode = WAL;");
                for (String sql : splitStatements(schemaSql)) {
                    stmt.execute(sql);
                }
            }
            initializeSystemFundingAccounts(conn);
            initializeGenesisCheckpoint(conn);
        }
    }

    private void initializeSystemFundingAccounts(Connection conn) throws SQLException {
        String sql = """
                INSERT OR IGNORE INTO ledger_accounts (account_id, account_type, wallet_id, currency_code)
                VALUES (?, 'SYSTEM_FUNDING', NULL, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            for (String currency : supportedCurrencies.codes()) {
                ps.setString(1, "SYSTEM_FUNDING_" + currency);
                ps.setString(2, currency);
                ps.executeUpdate();
            }
        }
    }

    private void initializeGenesisCheckpoint(Connection conn) throws SQLException {
        byte[] genesisHash = LedgerHashChainService.GENESIS_HASH;
        byte[] genesisMac = hashChainService.computeCheckpointMac(0L, genesisHash);
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String sql = """
                INSERT OR IGNORE INTO integrity_checkpoints
                    (checkpoint_id, chain_head_hash, last_entry_sequence, checkpoint_mac, updated_at)
                VALUES (1, ?, 0, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setBytes(1, genesisHash);
            ps.setBytes(2, genesisMac);
            ps.setString(3, now);
            ps.executeUpdate();
        }
    }

    private String loadSchemaSql() throws IOException {
        ClassPathResource resource = new ClassPathResource("schema.sql");
        return new String(resource.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    /**
     * Splits SQL script into statements while keeping {@code BEGIN ... END;} trigger
     * blocks intact.
     */
    private static java.util.List<String> splitStatements(String sql) {
        java.util.List<String> statements = new java.util.ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean insideTrigger = false;
        for (String line : sql.split("\\R")) {
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("--")) {
                continue;
            }
            String upper = trimmed.toUpperCase();
            if (upper.startsWith("CREATE TRIGGER")) {
                insideTrigger = true;
            }
            current.append(line).append('\n');
            if (insideTrigger) {
                if (upper.equals("END;")) {
                    statements.add(current.toString().trim());
                    current.setLength(0);
                    insideTrigger = false;
                }
            } else if (trimmed.endsWith(";")) {
                statements.add(current.toString().trim());
                current.setLength(0);
            }
        }
        if (!current.isEmpty() && !current.toString().isBlank()) {
            statements.add(current.toString().trim());
        }
        return statements;
    }
}
