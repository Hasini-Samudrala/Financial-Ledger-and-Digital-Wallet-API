package com.finledger.api.transfer;

import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;

/**
 * Parameterised JDBC repository for {@code transfers}.
 */
@Repository
public class TransferRepository {

    public void insert(Connection conn, TransferRecord transfer) throws SQLException {
        String sql = """
                INSERT INTO transfers
                    (transfer_id, source_wallet_id, destination_wallet_id, currency_code,
                     amount_minor, status, reversal_of_transfer_id, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, transfer.transferId());
            ps.setString(2, transfer.sourceWalletId());
            ps.setString(3, transfer.destinationWalletId());
            ps.setString(4, transfer.currencyCode());
            ps.setLong(5, transfer.amountMinor());
            ps.setString(6, transfer.status());
            if (transfer.reversalOf() != null) {
                ps.setString(7, transfer.reversalOf());
            } else {
                ps.setNull(7, Types.VARCHAR);
            }
            ps.setString(8, transfer.createdAt());
            ps.executeUpdate();
        }
    }

    public TransferRecord findById(Connection conn, String transferId) throws SQLException {
        String sql = """
                SELECT transfer_id, source_wallet_id, destination_wallet_id, currency_code,
                       amount_minor, status, reversal_of_transfer_id, created_at
                FROM transfers
                WHERE transfer_id = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, transferId);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new TransferRecord(
                        rs.getString("transfer_id"),
                        rs.getString("source_wallet_id"),
                        rs.getString("destination_wallet_id"),
                        rs.getString("currency_code"),
                        rs.getLong("amount_minor"),
                        rs.getString("status"),
                        rs.getString("reversal_of_transfer_id"),
                        rs.getString("created_at"));
            }
        }
    }

    public void markReversed(Connection conn, String transferId) throws SQLException {
        String sql = """
                UPDATE transfers
                SET status = 'REVERSED'
                WHERE transfer_id = ? AND status = 'COMPLETED'
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, transferId);
            int updated = ps.executeUpdate();
            if (updated != 1) {
                throw new IllegalStateException("Expected 1 transfer row to be marked REVERSED.");
            }
        }
    }
}
