package com.finledger.api.user;

import org.springframework.stereotype.Repository;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;

/**
 * Parameterised JDBC repository for the {@code users} table.
 */
@Repository
public class UserRepository {

    public void insert(Connection conn, UserRecord user) throws SQLException {
        String sql = "INSERT INTO users (user_id, username, api_key_hash, api_key_salt) VALUES (?, ?, ?, ?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, user.userId());
            ps.setString(2, user.username());
            ps.setBytes(3, user.apiKeyHash());
            ps.setBytes(4, user.apiKeySalt());
            ps.executeUpdate();
        }
    }

    public UserRecord findById(Connection conn, String userId) throws SQLException {
        String sql = "SELECT user_id, username, api_key_hash, api_key_salt FROM users WHERE user_id = ?";
        return querySingle(conn, sql, userId);
    }

    public UserRecord findByUsernameIgnoreCase(Connection conn, String username) throws SQLException {
        String sql = "SELECT user_id, username, api_key_hash, api_key_salt FROM users WHERE LOWER(username) = LOWER(?)";
        return querySingle(conn, sql, username);
    }

    public boolean existsByUsernameIgnoreCase(Connection conn, String username) throws SQLException {
        String sql = "SELECT 1 FROM users WHERE LOWER(username) = LOWER(?)";
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, username);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next();
            }
        }
    }

    private static UserRecord querySingle(Connection conn, String sql, String param) throws SQLException {
        try (PreparedStatement ps = conn.prepareStatement(sql)) {
            ps.setString(1, param);
            try (ResultSet rs = ps.executeQuery()) {
                if (!rs.next()) {
                    return null;
                }
                return new UserRecord(
                        rs.getString("user_id"),
                        rs.getString("username"),
                        rs.getBytes("api_key_hash"),
                        rs.getBytes("api_key_salt"));
            }
        }
    }
}
