package com.finledger.api;

import com.finledger.api.db.SqliteTransactionExecutor;
import com.finledger.api.ledger.LedgerHashChainService;
import com.finledger.api.transfer.TransferRepository;
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

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class ReversalAndLedgerHistoryIntegrationTest {

    private static final String ADMIN_KEY = "test-admin-secret-key-for-junit";

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private SqliteTransactionExecutor txExecutor;

    @Autowired
    private TransferRepository transferRepository;

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
    void successfulReversalCreatesCompensatingEntriesRestoresBalancesAndPreservesOriginalEntries() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 10000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 2000L);

        String transferId = executeTransfer(alice, bob, "INR", 4000L);
        assertWalletBalance(alice, 6000L);
        assertWalletBalance(bob, 6000L);

        long originalEntryCount = countWalletEntries(alice.walletId());

        // Reverse transfer -> 201 Created
        MvcResult revRes = mockMvc.perform(post("/transfers/" + transferId + "/reversal")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").isNotEmpty())
                .andExpect(jsonPath("$.sourceWalletId").value(bob.walletId()))
                .andExpect(jsonPath("$.destinationWalletId").value(alice.walletId()))
                .andExpect(jsonPath("$.currencyCode").value("INR"))
                .andExpect(jsonPath("$.amountMinor").value(4000))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andExpect(jsonPath("$.reversalOf").value(transferId))
                .andReturn();

        String reversalTransferId = objectMapper.readTree(revRes.getResponse().getContentAsString())
                .get("transferId").asText();

        // Balances restored
        assertWalletBalance(alice, 10000L);
        assertWalletBalance(bob, 2000L);

        // Original transfer marked REVERSED
        mockMvc.perform(get("/transfers/" + transferId)
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVERSED"));

        // Original entries unchanged + new compensating entry added
        assertEquals(originalEntryCount + 1, countWalletEntries(alice.walletId()));

        // Compensating REVERSAL ledger transaction is balanced & checkpoint MAC is valid
        txExecutor.executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT SUM(le.debit_minor), SUM(le.credit_minor), COUNT(*)
                    FROM ledger_entries le
                    JOIN ledger_transactions lt ON lt.ledger_transaction_id = le.transaction_id
                    WHERE lt.type = 'REVERSAL' AND lt.reference_id = ?
                    """)) {
                ps.setString(1, reversalTransferId);
                try (ResultSet rs = ps.executeQuery()) {
                    assertTrue(rs.next());
                    assertEquals(4000L, rs.getLong(1));
                    assertEquals(4000L, rs.getLong(2));
                    assertEquals(2, rs.getInt(3));
                }
            }
            try (PreparedStatement ps = conn.prepareStatement(
                    "SELECT last_entry_sequence, chain_head_hash, checkpoint_mac FROM integrity_checkpoints WHERE checkpoint_id = 1");
                 ResultSet rs = ps.executeQuery()) {
                assertTrue(rs.next());
                assertArrayEquals(
                        hashChainService.computeCheckpointMac(rs.getLong("last_entry_sequence"), rs.getBytes("chain_head_hash")),
                        rs.getBytes("checkpoint_mac"));
            }
            return null;
        });

        // Reversing an already-reversed transfer -> 409 TRANSFER_NOT_REVERSIBLE
        mockMvc.perform(post("/transfers/" + transferId + "/reversal")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_REVERSIBLE"));

        // Reversing a reversal transfer itself -> 409 TRANSFER_NOT_REVERSIBLE
        mockMvc.perform(post("/transfers/" + reversalTransferId + "/reversal")
                        .header("X-User-API-Key", bob.apiKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_REVERSIBLE"));
    }

    @Test
    void reversalAccessControlValidationAndInsufficientDestinationFunds() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 10000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);
        UserAndWallet charlie = createUserWithFundedWallet("INR", 0L);

        String transferId = executeTransfer(alice, bob, "INR", 5000L);

        // Missing API key -> 401
        mockMvc.perform(post("/transfers/" + transferId + "/reversal"))
                .andExpect(status().isUnauthorized());

        // Destination owner or third party cannot reverse -> 403 FORBIDDEN
        mockMvc.perform(post("/transfers/" + transferId + "/reversal")
                        .header("X-User-API-Key", bob.apiKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        mockMvc.perform(post("/transfers/" + transferId + "/reversal")
                        .header("X-User-API-Key", charlie.apiKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // Invalid UUID -> 400; nonexistent transfer -> 404
        mockMvc.perform(post("/transfers/not-a-uuid/reversal")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/transfers/" + UUID.randomUUID() + "/reversal")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));

        // Bob spends 3000 -> only 2000 left, cannot reverse 5000 -> 409 INSUFFICIENT_FUNDS
        executeTransfer(bob, charlie, "INR", 3000L);
        mockMvc.perform(post("/transfers/" + transferId + "/reversal")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));

        // Original transfer remains COMPLETED and balances unchanged
        mockMvc.perform(get("/transfers/" + transferId)
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertWalletBalance(alice, 5000L);
        assertWalletBalance(bob, 2000L);
    }

    @Test
    void reversalRollbackLeavesNoPartialChanges() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 5000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);
        String transferId = executeTransfer(alice, bob, "INR", 2000L);

        assertThrows(RuntimeException.class, () -> txExecutor.executeWrite(conn -> {
            transferRepository.markReversed(conn, transferId);
            throw new RuntimeException("Simulated failure mid-reversal");
        }));

        mockMvc.perform(get("/transfers/" + transferId)
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("COMPLETED"));
        assertWalletBalance(alice, 3000L);
        assertWalletBalance(bob, 2000L);
    }

    @Test
    void concurrentReversalAttemptsCreateOnlyOneReversal() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 10000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);
        String transferId = executeTransfer(alice, bob, "INR", 6000L);

        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return mockMvc.perform(post("/transfers/" + transferId + "/reversal")
                                .header("X-User-API-Key", alice.apiKey()))
                        .andReturn();
            }));
        }
        ready.await();
        go.countDown();

        int created = 0;
        int conflict = 0;
        for (Future<MvcResult> f : futures) {
            int sc = f.get().getResponse().getStatus();
            if (sc == 201) {
                created++;
            } else if (sc == 409) {
                conflict++;
            } else {
                throw new AssertionError("Unexpected status: " + sc);
            }
        }
        pool.shutdown();

        assertEquals(1, created);
        assertEquals(threads - 1, conflict);
        assertWalletBalance(alice, 10000L);
        assertWalletBalance(bob, 0L);
    }

    @Test
    void ledgerHistoryPaginationOrderingValidationOwnershipAndReversalIntegrity() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 50000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);
        UserAndWallet emptyUser = createUserWithFundedWallet("USD", 0L);

        // Empty history -> 200, entries: [], nextCursor: null
        mockMvc.perform(get("/wallets/" + emptyUser.walletId() + "/ledger-entries")
                        .header("X-User-API-Key", emptyUser.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.walletId").value(emptyUser.walletId()))
                .andExpect(jsonPath("$.entries").isEmpty())
                .andExpect(jsonPath("$.nextCursor").doesNotExist());

        // Auth, ownership, and invalid query params
        mockMvc.perform(get("/wallets/" + alice.walletId() + "/ledger-entries"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/wallets/" + alice.walletId() + "/ledger-entries")
                        .header("X-User-API-Key", bob.apiKey()))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/wallets/" + UUID.randomUUID() + "/ledger-entries")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isNotFound());

        mockMvc.perform(get("/wallets/" + alice.walletId() + "/ledger-entries?cursor=-1")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/wallets/" + alice.walletId() + "/ledger-entries?limit=0")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(get("/wallets/" + alice.walletId() + "/ledger-entries?cursor=abc")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        // Create 22 more entries on Alice's wallet (1 funding + 22 transfers = 23 entries total)
        String lastTransferId = null;
        for (int i = 0; i < 22; i++) {
            lastTransferId = executeTransfer(alice, bob, "INR", 100L);
        }

        // Default limit = 20 -> returns 20 entries and non-null nextCursor, no hash fields exposed
        MvcResult page1 = mockMvc.perform(get("/wallets/" + alice.walletId() + "/ledger-entries")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(20))
                .andExpect(jsonPath("$.nextCursor").isNumber())
                .andReturn();

        JsonNode page1Json = objectMapper.readTree(page1.getResponse().getContentAsString());
        JsonNode entries1 = page1Json.get("entries");
        long prevSeq = 0;
        for (JsonNode entry : entries1) {
            long seq = entry.get("sequenceNo").asLong();
            assertTrue(seq > prevSeq);
            prevSeq = seq;
            assertFalse(entry.has("entryHash"));
            assertFalse(entry.has("previousHash"));
        }
        long cursor1 = page1Json.get("nextCursor").asLong();
        assertEquals(prevSeq, cursor1);

        // Reverse lastTransferId BETWEEN page 1 and page 2 (adds a new entry after cursor1!)
        mockMvc.perform(post("/transfers/" + lastTransferId + "/reversal")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isCreated());

        // Page 2 with ?cursor=<cursor1> -> returns remaining 3 transfers + 1 reversal = 4 entries, nextCursor null
        MvcResult page2 = mockMvc.perform(get("/wallets/" + alice.walletId() + "/ledger-entries?cursor=" + cursor1)
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(4))
                .andExpect(jsonPath("$.nextCursor").doesNotExist())
                .andReturn();

        JsonNode entries2 = objectMapper.readTree(page2.getResponse().getContentAsString()).get("entries");
        for (JsonNode entry : entries2) {
            long seq = entry.get("sequenceNo").asLong();
            assertTrue(seq > prevSeq); // strictly greater than page 1's last entry (no duplicates)
            prevSeq = seq;
        }

        // Maximum limit enforcement: limit=200 clamped to 100, returns all 24 entries in 1 page
        mockMvc.perform(get("/wallets/" + alice.walletId() + "/ledger-entries?limit=200")
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entries.length()").value(24))
                .andExpect(jsonPath("$.nextCursor").doesNotExist());
    }

    private record UserAndWallet(String userId, String apiKey, String walletId) {
    }

    private UserAndWallet createUserWithFundedWallet(String currency, long amountMinor) throws Exception {
        String username = "u_" + UUID.randomUUID().toString().replace("-", "").substring(0, 10);
        MvcResult reg = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode userJson = objectMapper.readTree(reg.getResponse().getContentAsString());
        String userId = userJson.get("userId").asText();
        String apiKey = userJson.get("apiKey").asText();

        MvcResult w = mockMvc.perform(post("/users/" + username + "/wallets")
                        .header("X-User-API-Key", apiKey)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String walletId = objectMapper.readTree(w.getResponse().getContentAsString()).get("walletId").asText();

        if (amountMinor > 0) {
            mockMvc.perform(post("/admin/funding")
                            .header("X-Admin-API-Key", ADMIN_KEY)
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"walletId\":\"" + walletId + "\",\"amountMinor\":" + amountMinor
                                    + ",\"currencyCode\":\"" + currency + "\"}"))
                    .andExpect(status().isOk());
        }
        return new UserAndWallet(userId, apiKey, walletId);
    }

    private String executeTransfer(UserAndWallet from, UserAndWallet to, String currency, long amount) throws Exception {
        MvcResult res = mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", from.apiKey())
                        .header("Idempotency-Key", "k-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceWalletId\":\"" + from.walletId()
                                + "\",\"destinationWalletId\":\"" + to.walletId()
                                + "\",\"currencyCode\":\"" + currency
                                + "\",\"amountMinor\":" + amount + "}"))
                .andExpect(status().isCreated())
                .andReturn();
        return objectMapper.readTree(res.getResponse().getContentAsString()).get("transferId").asText();
    }

    private void assertWalletBalance(UserAndWallet owner, long expected) throws Exception {
        mockMvc.perform(get("/wallets/" + owner.walletId())
                        .header("X-User-API-Key", owner.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(expected));
    }

    private long countWalletEntries(String walletId) {
        return txExecutor.executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT COUNT(*)
                    FROM ledger_entries le
                    JOIN ledger_accounts la ON la.account_id = le.account_id
                    WHERE la.wallet_id = ?
                    """)) {
                ps.setString(1, walletId);
                try (ResultSet rs = ps.executeQuery()) {
                    rs.next();
                    return rs.getLong(1);
                }
            }
        });
    }
}
