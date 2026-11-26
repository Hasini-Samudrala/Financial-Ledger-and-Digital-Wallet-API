package com.finledger.api.config;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.sqlite.SQLiteConfig;
import org.sqlite.SQLiteDataSource;

import javax.sql.DataSource;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Configures the SQLite {@link DataSource} with per-connection durability and
 * foreign-key PRAGMAs (ADR-003).
 */
@Configuration
public class SqliteConfig {

    @Bean
    public DataSource dataSource(
            @Value("${spring.datasource.url}") String jdbcUrl,
            AppProperties appProperties) throws IOException {
        ensureParentDirectory(jdbcUrl);

        SQLiteConfig sqliteConfig = new SQLiteConfig();
        sqliteConfig.enforceForeignKeys(true);
        sqliteConfig.setSynchronous(SQLiteConfig.SynchronousMode.FULL);
        sqliteConfig.setBusyTimeout(appProperties.getSqlite().getBusyTimeoutMs());

        SQLiteDataSource delegate = new SQLiteDataSource(sqliteConfig);
        delegate.setUrl(jdbcUrl);

        int busyTimeoutMs = appProperties.getSqlite().getBusyTimeoutMs();
        return new AbstractDataSource() {
            @Override
            public Connection getConnection() throws SQLException {
                Connection conn = delegate.getConnection();
                applyPerConnectionPragmas(conn, busyTimeoutMs);
                return conn;
            }

            @Override
            public Connection getConnection(String username, String password) throws SQLException {
                Connection conn = delegate.getConnection(username, password);
                applyPerConnectionPragmas(conn, busyTimeoutMs);
                return conn;
            }
        };
    }

    private static void applyPerConnectionPragmas(Connection conn, int busyTimeoutMs) throws SQLException {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("PRAGMA foreign_keys = ON;");
            stmt.execute("PRAGMA synchronous = FULL;");
            stmt.execute("PRAGMA busy_timeout = " + busyTimeoutMs + ";");
        }
    }

    private static void ensureParentDirectory(String jdbcUrl) throws IOException {
        String prefix = "jdbc:sqlite:";
        if (!jdbcUrl.startsWith(prefix)) {
            return;
        }
        String filePart = jdbcUrl.substring(prefix.length());
        int queryIdx = filePart.indexOf('?');
        if (queryIdx >= 0) {
            filePart = filePart.substring(0, queryIdx);
        }
        if (filePart.isBlank() || ":memory:".equals(filePart) || filePart.startsWith("file::memory:")) {
            return;
        }
        Path dbPath = Paths.get(filePart).toAbsolutePath();
        Path parent = dbPath.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
    }
}
