package com.finledger.api.transfer;

import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Parameterised JDBC repository for {@code idempotency_records}.
 */
@Repository
public class IdempotencyRepository {

    public IdempotencyRecord findByUserAndKey(Connection conn, String userId, String idempotencyKey)
            throws SQLException {
        String sql = """
                SELECT user_id, idempotency_key, request_fingerprint, transfer_id, created_at
                FROM idempotency_records
                WHERE user_id = ? AND idempotency_key = ?
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, userId);
            ps.setString(2, idempotencyKey);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new IdempotencyRecord(
                        rs.getString("user_id"),
                        rs.getString("idempotency_key"),
                        rs.getBytes("request_fingerprint"),
                        rs.getString("transfer_id"),
                        rs.getString("created_at"));
            }
        }
    }

    public void insert(Connection conn, IdempotencyRecord record) throws SQLException {
        String sql = """
                INSERT INTO idempotency_records
                    (user_id, idempotency_key, request_fingerprint, transfer_id, created_at)
                VALUES (?, ?, ?, ?, ?)
                """;
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, record.userId());
            ps.setString(2, record.idempotencyKey());
            ps.setBytes(3, record.requestFingerprint());
            ps.setString(4, record.transferId());
            ps.setString(5, record.createdAt());
            ps.executeUpdate();
        }
    }
}
