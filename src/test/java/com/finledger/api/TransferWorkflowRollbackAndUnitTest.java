package com.finledger.api;

import com.finledger.api.exception.DatabaseBusyException;
import com.finledger.api.exception.GlobalExceptionHandler;
import com.finledger.api.ledger.LedgerEntryDraft;
import com.finledger.api.ledger.LedgerRepository;
import com.finledger.api.transfer.TransferService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.context.WebApplicationContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
class TransferWorkflowRollbackAndUnitTest {

    private static final String ADMIN_KEY = "test-admin-secret-key-for-junit";

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerTempDb(DynamicPropertyRegistry registry) {
        String dbFile = tempDir.resolve("rollback_verify_" + UUID.randomUUID() + ".db").toString();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private TransferService transferService;

    @Autowired
    private DataSource dataSource;

    @Test
    void realTransferWorkflowRollbackLeavesZeroPartialState() throws Exception {
        MockMvc mockMvc = MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();

        // Register Alice & Bob, create wallets, fund Alice
        JsonNode alice = registerAndCreateWallet(mockMvc, "rb_alice", "INR", 10000L);
        JsonNode bob = registerAndCreateWallet(mockMvc, "rb_bob", "INR", 0L);

        long transfersBefore = count("transfers");
        long txBefore = count("ledger_transactions");
        long entriesBefore = count("ledger_entries");
        long idemBefore = count("idempotency_records");
        long checkpointSeqBefore = checkpointSeq();

        // Inject a test-only DB trigger that aborts on idempotency_records INSERT
        // (after TransferService has already written transfers, ledger_transactions,
        // ledger_entries, updated wallet balances, and updated integrity_checkpoints).
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("""
                    CREATE TRIGGER test_abort_idempotency_insert
                    BEFORE INSERT ON idempotency_records
                    BEGIN
                        SELECT RAISE(ABORT, 'Simulated failure before commit');
                    END;
                    """);
        }

        assertThrows(IllegalStateException.class, () -> transferService.createTransfer(
                alice.get("userId").asText(),
                "rb-key-1",
                alice.get("walletId").asText(),
                bob.get("walletId").asText(),
                "INR",
                3500L));

        // Verify ALL tables & balances rolled back completely
        assertEquals(transfersBefore, count("transfers"));
        assertEquals(txBefore, count("ledger_transactions"));
        assertEquals(entriesBefore, count("ledger_entries"));
        assertEquals(idemBefore, count("idempotency_records"));
        assertEquals(checkpointSeqBefore, checkpointSeq());

        mockMvc.perform(get("/wallets/" + alice.get("walletId").asText())
                        .header("X-User-API-Key", alice.get("apiKey").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(10000));

        mockMvc.perform(get("/wallets/" + bob.get("walletId").asText())
                        .header("X-User-API-Key", bob.get("apiKey").asText()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(0));
    }

    @Test
    void fingerprintIsDeterministicAndSensitiveToEveryField() {
        byte[] base = TransferService.computeFingerprint("u1", "w1", "w2", "INR", 5000L);
        byte[] same = TransferService.computeFingerprint("u1", "w1", "w2", "INR", 5000L);
        assertArrayEquals(base, same);
        assertEquals(32, base.length);

        assertFalse(Arrays.equals(base, TransferService.computeFingerprint("u2", "w1", "w2", "INR", 5000L)));
        assertFalse(Arrays.equals(base, TransferService.computeFingerprint("u1", "w9", "w2", "INR", 5000L)));
        assertFalse(Arrays.equals(base, TransferService.computeFingerprint("u1", "w1", "w9", "INR", 5000L)));
        assertFalse(Arrays.equals(base, TransferService.computeFingerprint("u1", "w1", "w2", "USD", 5000L)));
        assertFalse(Arrays.equals(base, TransferService.computeFingerprint("u1", "w1", "w2", "INR", 5001L)));
    }

    @Test
    void ledgerAccountingValidationRejectsInvalidAndUnbalancedDrafts() {
        // Null or empty
        assertThrows(IllegalArgumentException.class, () -> LedgerRepository.validateBalanced("INR", null));
        assertThrows(IllegalArgumentException.class, () -> LedgerRepository.validateBalanced("INR", List.of()));

        // Mixed currency
        assertThrows(IllegalArgumentException.class, () -> LedgerRepository.validateBalanced("INR", List.of(
                new LedgerEntryDraft("e1", "a1", "INR", 100, 0),
                new LedgerEntryDraft("e2", "a2", "USD", 0, 100))));

        // Both sides positive or both zero or negative
        assertThrows(IllegalArgumentException.class, () -> LedgerRepository.validateBalanced("INR", List.of(
                new LedgerEntryDraft("e1", "a1", "INR", 100, 100))));
        assertThrows(IllegalArgumentException.class, () -> LedgerRepository.validateBalanced("INR", List.of(
                new LedgerEntryDraft("e1", "a1", "INR", 0, 0))));
        assertThrows(IllegalArgumentException.class, () -> LedgerRepository.validateBalanced("INR", List.of(
                new LedgerEntryDraft("e1", "a1", "INR", -50, 0),
                new LedgerEntryDraft("e2", "a2", "INR", 0, -50))));

        // Unbalanced debit != credit
        assertThrows(IllegalArgumentException.class, () -> LedgerRepository.validateBalanced("INR", List.of(
                new LedgerEntryDraft("e1", "a1", "INR", 100, 0),
                new LedgerEntryDraft("e2", "a2", "INR", 0, 99))));

        // Valid balanced drafts succeed
        LedgerRepository.validateBalanced("INR", List.of(
                new LedgerEntryDraft("e1", "a1", "INR", 500, 0),
                new LedgerEntryDraft("e2", "a2", "INR", 0, 500)));
    }

    @Test
    void databaseBusyMapsTo503WithRetryAfterHeader() {
        GlobalExceptionHandler handler = new GlobalExceptionHandler();
        ResponseEntity<Map<String, Object>> resp = handler.handleBusy(
                new DatabaseBusyException("Database is temporarily busy."));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, resp.getStatusCode());
        assertEquals("1", resp.getHeaders().getFirst("Retry-After"));
    }

    private JsonNode registerAndCreateWallet(MockMvc mockMvc, String username, String currency, long fundAmount)
            throws Exception {
        MvcResult reg = mockMvc.perform(post("/users")
                        .contentType("application/json")
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode u = objectMapper.readTree(reg.getResponse().getContentAsString());
        String apiKey = u.get("apiKey").asText();

        MvcResult wal = mockMvc.perform(post("/users/" + username + "/wallets")
                        .header("X-User-API-Key", apiKey)
                        .contentType("application/json")
                        .content("{\"currencyCode\":\"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode w = objectMapper.readTree(wal.getResponse().getContentAsString());
        String walletId = w.get("walletId").asText();

        if (fundAmount > 0) {
            mockMvc.perform(post("/admin/funding")
                            .header("X-Admin-API-Key", ADMIN_KEY)
                            .contentType("application/json")
                            .content("{\"walletId\":\"" + walletId + "\",\"amountMinor\":" + fundAmount
                                    + ",\"currencyCode\":\"" + currency + "\"}"))
                    .andExpect(status().isOk());
        }
        return objectMapper.createObjectNode()
                .put("userId", u.get("userId").asText())
                .put("apiKey", apiKey)
                .put("walletId", walletId);
    }

    private long count(String table) throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM " + table);
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }

    private long checkpointSeq() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement(
                     "SELECT last_entry_sequence FROM integrity_checkpoints WHERE checkpoint_id = 1");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
