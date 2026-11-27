package com.finledger.api;

import com.finledger.api.db.SqliteTransactionExecutor;
import com.finledger.api.ledger.LedgerHashChainService;
import com.finledger.api.user.UserRecord;
import com.finledger.api.user.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.Arrays;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ApiMilestoneIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-secret-key-for-junit";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqliteTransactionExecutor txExecutor;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private LedgerHashChainService hashChainService;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void healthEndpointIsPublicAndReturnsUp() throws Exception {
        mockMvc.perform(get("/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));
    }

    @Test
    void userRegistrationValidationDuplicateAndAuthenticationEnforced() throws Exception {
        String username = uniqueUsername("Alice");
        MvcResult reg = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").isNotEmpty())
                .andExpect(jsonPath("$.username").value(username))
                .andExpect(jsonPath("$.apiKey").isNotEmpty())
                .andReturn();

        JsonNode json = objectMapper.readTree(reg.getResponse().getContentAsString());
        String userId = json.get("userId").asText();
        String apiKey = json.get("apiKey").asText();
        assertTrue(apiKey.startsWith("flw_" + userId + "."));

        UserRecord stored = txExecutor.executeRead(conn -> userRepository.findById(conn, userId));
        assertNotNull(stored);
        assertEquals(username, stored.username());
        assertFalse(Arrays.equals(apiKey.getBytes(StandardCharsets.UTF_8), stored.apiKeyHash()));

        // Duplicate username (exact & case-insensitive) -> 409 DUPLICATE_USERNAME
        mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username.toLowerCase() + "\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_USERNAME"));

        // Invalid usernames -> 400 VALIDATION_ERROR
        for (String bad : new String[]{"ab", "a".repeat(21), "bad-name", "bad name", ""}) {
            mockMvc.perform(post("/users")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"" + bad + "\"}"))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
        }

        // Missing, malformed, and wrong-secret keys return 401
        mockMvc.perform(get("/wallets/" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"));

        mockMvc.perform(get("/wallets/" + UUID.randomUUID())
                        .header("X-User-API-Key", "malformed-key"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"));

        String wrongSecretKey = "flw_" + userId + "." + "0".repeat(64);
        mockMvc.perform(get("/wallets/" + UUID.randomUUID())
                        .header("X-User-API-Key", wrongSecretKey))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"));
    }

    @Test
    void walletCreationAndLookupByUsernameAndUuidWithOwnershipAndValidation() throws Exception {
        JsonNode user1 = registerUser(uniqueUsername("UserOne"));
        JsonNode user2 = registerUser(uniqueUsername("UserTwo"));
        String user1Id = user1.get("userId").asText();
        String user1Name = user1.get("username").asText();
        String user1Key = user1.get("apiKey").asText();
        String user2Key = user2.get("apiKey").asText();

        // Create INR wallet by username -> 201, zero initial balance
        MvcResult walletRes = mockMvc.perform(post("/users/" + user1Name + "/wallets")
                        .header("X-User-API-Key", user1Key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.userId").value(user1Id))
                .andExpect(jsonPath("$.currencyCode").value("INR"))
                .andExpect(jsonPath("$.balanceMinor").value(0))
                .andReturn();

        String walletId = objectMapper.readTree(walletRes.getResponse().getContentAsString())
                .get("walletId").asText();

        // Duplicate currency (case-insensitive username) -> 409 DUPLICATE_WALLET
        mockMvc.perform(post("/users/" + user1Name.toUpperCase() + "/wallets")
                        .header("X-User-API-Key", user1Key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"INR\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("DUPLICATE_WALLET"));

        // Unsupported currency -> 400 UNSUPPORTED_CURRENCY
        mockMvc.perform(post("/users/" + user1Name + "/wallets")
                        .header("X-User-API-Key", user1Key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"EUR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_CURRENCY"));

        // Invalid username format -> 400 VALIDATION_ERROR; nonexistent username -> 404 USER_NOT_FOUND
        mockMvc.perform(get("/users/bad-name/wallets/INR")
                        .header("X-User-API-Key", user1Key))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/users/no_such_user_99/wallets/INR")
                        .header("X-User-API-Key", user1Key))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("USER_NOT_FOUND"));

        // Malformed UUID -> 400 VALIDATION_ERROR
        mockMvc.perform(get("/wallets/not-a-uuid")
                        .header("X-User-API-Key", user1Key))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        // Ownership enforcement on username & UUID endpoints -> 403 FORBIDDEN
        mockMvc.perform(post("/users/" + user1Name + "/wallets")
                        .header("X-User-API-Key", user2Key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"USD\"}"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(get("/users/" + user1Name + "/wallets/INR")
                        .header("X-User-API-Key", user2Key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(get("/wallets/" + walletId)
                        .header("X-User-API-Key", user2Key))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // Owner can retrieve wallet by username + currency AND by UUID
        mockMvc.perform(get("/users/" + user1Name.toLowerCase() + "/wallets/INR")
                        .header("X-User-API-Key", user1Key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletId))
                .andExpect(jsonPath("$.userId").value(user1Id))
                .andExpect(jsonPath("$.currencyCode").value("INR"))
                .andExpect(jsonPath("$.balanceMinor").value(0));

        mockMvc.perform(get("/wallets/" + walletId)
                        .header("X-User-API-Key", user1Key))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletId))
                .andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void adminFundingUpdatesBalanceDoubleEntryLedgerHashChainAndCheckpoint() throws Exception {
        JsonNode user = registerUser(uniqueUsername("Funder"));
        String userName = user.get("username").asText();
        String userKey = user.get("apiKey").asText();

        MvcResult walletRes = mockMvc.perform(post("/users/" + userName + "/wallets")
                        .header("X-User-API-Key", userKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"INR\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String walletId = objectMapper.readTree(walletRes.getResponse().getContentAsString())
                .get("walletId").asText();

        // Admin auth enforced
        mockMvc.perform(post("/admin/funding")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletId\":\"" + walletId + "\",\"amountMinor\":50000,\"currencyCode\":\"INR\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"));

        mockMvc.perform(post("/admin/funding")
                        .header("X-Admin-API-Key", "wrong-admin-key")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletId\":\"" + walletId + "\",\"amountMinor\":50000,\"currencyCode\":\"INR\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"));

        // Invalid amount -> 400
        mockMvc.perform(post("/admin/funding")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletId\":\"" + walletId + "\",\"amountMinor\":0,\"currencyCode\":\"INR\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_AMOUNT"));

        // Successful funding -> 200 and no hash fields exposed
        MvcResult fundRes = mockMvc.perform(post("/admin/funding")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletId\":\"" + walletId + "\",\"amountMinor\":50000,\"currencyCode\":\"INR\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(walletId))
                .andExpect(jsonPath("$.balanceMinor").value(50000))
                .andExpect(jsonPath("$.currencyCode").value("INR"))
                .andReturn();

        JsonNode fundJson = objectMapper.readTree(fundRes.getResponse().getContentAsString());
        assertFalse(fundJson.has("entryHash"));
        assertFalse(fundJson.has("previousHash"));

        // Username lookup reflects updated balance
        mockMvc.perform(get("/users/" + userName + "/wallets/INR")
                        .header("X-User-API-Key", userKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(50000));

        // Verify ledger-derived balance matches stored balance & checkpoint MAC is valid
        txExecutor.executeRead(conn -> {
            long derivedBalance = 0;
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT COALESCE(SUM(le.credit_minor - le.debit_minor), 0)
                    FROM ledger_entries le
                    JOIN ledger_accounts la ON la.account_id = le.account_id
                    WHERE la.wallet_id = ?
                    """)) {
                ps.setString(1, walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    derivedBalance = rs.getLong(1);
                }
            }
            assertEquals(50000L, derivedBalance);

            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT last_entry_sequence, chain_head_hash, checkpoint_mac FROM integrity_checkpoints WHERE checkpoint_id = 1");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                long seq = rs.getLong("last_entry_sequence");
                byte[] head = rs.getBytes("chain_head_hash");
                byte[] mac = rs.getBytes("checkpoint_mac");
                assertTrue(seq >= 2);
                assertArrayEquals(hashChainService.computeCheckpointMac(seq, head), mac);
            }
            return null;
        });

        // Currency mismatch on funding -> 400 and balance unchanged
        mockMvc.perform(post("/admin/funding")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletId\":\"" + walletId + "\",\"amountMinor\":1000,\"currencyCode\":\"USD\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CURRENCY_MISMATCH"));

        mockMvc.perform(get("/wallets/" + walletId)
                        .header("X-User-API-Key", userKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(50000));
    }

    private JsonNode registerUser(String username) throws Exception {
        MvcResult res = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString());
    }

    private static String uniqueUsername(String prefix) {
        String suffix = UUID.randomUUID().toString().replace("-", "").substring(0, 8);
        return prefix + "_" + suffix;
    }
}
