package com.finledger.api.db;

import com.finledger.api.config.AppProperties;
import com.finledger.api.exception.DatabaseBusyException;
import org.springframework.stereotype.Component;
import org.sqlite.SQLiteErrorCode;
import org.sqlite.SQLiteException;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;

/**
 * Executes explicit SQLite transactions using {@code BEGIN IMMEDIATE} with
 * {@code autoCommit = true}, avoiding Xerial's nested-transaction issue (ADR-003).
 */
@Component
public class SqliteTransactionExecutor {

    @FunctionalInterface
    public interface SqlWork<T> {
        T execute(Connection conn) throws SQLException;
    }

    private final DataSource dataSource;
    private final int maxRetries;
    private final long retryWaitMs;

    public SqliteTransactionExecutor(DataSource dataSource, AppProperties properties) {
        this.dataSource = dataSource;
        this.maxRetries = properties.getSqlite().getRetryCount();
        this.retryWaitMs = properties.getSqlite().getRetryWaitMs();
    }

    /**
     * Acquires a SQLite write lock via {@code BEGIN IMMEDIATE} before executing
     * {@code work}, retrying bounded times on {@code SQLITE_BUSY}.
     */
    public <T> T executeWrite(SqlWork<T> work) {
        int attempt = 0;
        while (true) {
            try {
                return runOnceWithBeginImmediate(work);
            } catch (SQLiteException ex) {
                if (isBusy(ex) && attempt < maxRetries) {
                    attempt++;
                    sleepQuietly();
                } else if (isBusy(ex)) {
                    throw new DatabaseBusyException(
                            "Database is temporarily busy. Please retry shortly.");
                } else {
                    throw new IllegalStateException("Database write failed.", ex);
                }
            } catch (SQLException ex) {
                throw new IllegalStateException("Database write failed.", ex);
            }
        }
    }

    public <T> T executeRead(SqlWork<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            return work.execute(conn);
        } catch (SQLException ex) {
            throw new IllegalStateException("Database read failed.", ex);
        }
    }

    /**
     * Executes multiple read queries inside a single {@code BEGIN DEFERRED}
     * transaction so all queries observe one consistent SQLite snapshot (FR-5.4).
     */
    public <T> T executeReadTransaction(SqlWork<T> work) {
        try (Connection conn = dataSource.getConnection()) {
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("BEGIN DEFERRED");
            }
            try {
                T result = work.execute(conn);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("COMMIT");
                }
                return result;
            } catch (RuntimeException | SQLException ex) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ROLLBACK");
                } catch (SQLException suppressed) {
                    ex.addSuppressed(suppressed);
                }
                throw ex;
            }
        } catch (SQLException ex) {
            throw new IllegalStateException("Database read transaction failed.", ex);
        }
    }

    private <T> T runOnceWithBeginImmediate(SqlWork<T> work) throws SQLException {
        try (Connection conn = dataSource.getConnection()) {
            // Keep autoCommit=true; calling setAutoCommit(false) makes Xerial emit BEGIN.
            try (Statement stmt = conn.createStatement()) {
                stmt.execute("BEGIN IMMEDIATE");
            }
            try {
                T result = work.execute(conn);
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("COMMIT");
                }
                return result;
            } catch (RuntimeException | SQLException ex) {
                try (Statement stmt = conn.createStatement()) {
                    stmt.execute("ROLLBACK");
                } catch (SQLException suppressed) {
                    ex.addSuppressed(suppressed);
                }
                throw ex;
            }
        }
    }

    private static boolean isBusy(SQLiteException ex) {
        SQLiteErrorCode code = ex.getResultCode();
        return code == SQLiteErrorCode.SQLITE_BUSY
                || code == SQLiteErrorCode.SQLITE_BUSY_SNAPSHOT
                || code == SQLiteErrorCode.SQLITE_BUSY_RECOVERY;
    }

    private void sleepQuietly() {
        try {
            Thread.sleep(retryWaitMs);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw new DatabaseBusyException("Interrupted while waiting for database lock.");
        }
    }
}
