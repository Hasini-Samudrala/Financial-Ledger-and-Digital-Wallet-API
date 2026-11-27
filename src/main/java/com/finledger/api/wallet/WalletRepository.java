package com.finledger.api.wallet;

import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Parameterised JDBC repository for {@code wallets}.
 */
@Repository
public class WalletRepository {

    public void insert(Connection conn, WalletRecord wallet) throws SQLException {
        String sql = """
                INSERT INTO wallets (wallet_id, user_id, currency_code, stored_balance_minor)
                VALUES (?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, wallet.walletId());
            ps.setString(2, wallet.userId());
            ps.setString(3, wallet.currencyCode());
            ps.setLong(4, wallet.balanceMinor());
            ps.executeUpdate();
        }
    }

    public boolean existsByUserAndCurrency(Connection conn, String userId, String currencyCode)
            throws SQLException {
        String sql = "SELECT 1 FROM wallets WHERE user_id = ? AND currency_code = ?";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, currencyCode);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    public WalletRecord findByUserAndCurrency(Connection conn, String userId, String currencyCode)
            throws SQLException {
        String sql = """
                SELECT wallet_id, user_id, currency_code, stored_balance_minor
                FROM wallets
                WHERE user_id = ? AND currency_code = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, currencyCode);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new WalletRecord(
                        rs.getString("wallet_id"),
                        rs.getString("user_id"),
                        rs.getString("currency_code"),
                        rs.getLong("stored_balance_minor"));
            }
        }
    }

    public WalletRecord findById(Connection conn, String walletId) throws SQLException {
        String sql = """
                SELECT wallet_id, user_id, currency_code, stored_balance_minor
                FROM wallets
                WHERE wallet_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, walletId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new WalletRecord(
                        rs.getString("wallet_id"),
                        rs.getString("user_id"),
                        rs.getString("currency_code"),
                        rs.getLong("stored_balance_minor"));
            }
        }
    }

    public void addBalance(Connection conn, String walletId, long deltaMinor) throws SQLException {
        String sql = """
                UPDATE wallets
                SET stored_balance_minor = stored_balance_minor + ?
                WHERE wallet_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setLong(1, deltaMinor);
            ps.setString(2, walletId);
            int updated = ps.executeUpdate();
            if (updated != 1) {
                throw new IllegalStateException("Wallet update failed for " + walletId);
            }
        }
    }
}
