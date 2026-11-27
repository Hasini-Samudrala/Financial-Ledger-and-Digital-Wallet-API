package com.finledger.api;

import com.finledger.api.db.SqliteTransactionExecutor;
import com.finledger.api.ledger.LedgerHashChainService;
import com.finledger.api.transfer.TransferRecord;
import com.finledger.api.transfer.TransferRepository;
import com.finledger.api.transfer.TransferService;
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
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
class TransferAndConcurrencyIntegrationTest {

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
    void successfulTransferUpdatesBalancesLedgerHashChainAndCheckpoint() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 10000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 2500L);
        String key = "tx-" + UUID.randomUUID();

        MvcResult res = mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 4000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.transferId").isNotEmpty())
                .andExpect(jsonPath("$.sourceWalletId").value(alice.walletId()))
                .andExpect(jsonPath("$.destinationWalletId").value(bob.walletId()))
                .andExpect(jsonPath("$.currencyCode").value("INR"))
                .andExpect(jsonPath("$.amountMinor").value(4000))
                .andExpect(jsonPath("$.status").value("COMPLETED"))
                .andReturn();

        String transferId = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("transferId").asText();

        assertWalletBalance(alice.apiKey(), alice.walletId(), 6000L);
        assertWalletBalance(bob.apiKey(), bob.walletId(), 6500L);

        // Verify balanced ledger entries for this transfer & valid checkpoint MAC
        txExecutor.executeRead(conn -> {
            try (PreparedStatement ps = conn.prepareStatement("""
                    SELECT SUM(le.debit_minor), SUM(le.credit_minor), COUNT(*)
                    FROM ledger_entries le
                    JOIN ledger_transactions lt ON lt.ledger_transaction_id = le.transaction_id
                    WHERE lt.type = 'TRANSFER' AND lt.reference_id = ?
                    """)) {
                ps.setString(1, transferId);
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
                long seq = rs.getLong("last_entry_sequence");
                byte[] head = rs.getBytes("chain_head_hash");
                byte[] mac = rs.getBytes("checkpoint_mac");
                assertArrayEquals(hashChainService.computeCheckpointMac(seq, head), mac);
            }
            return null;
        });
    }

    @Test
    void insufficientFundsReturns409AndIsNotCachedAsIdempotentSuccess() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 1000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);
        String key = "insufficient-" + UUID.randomUUID();

        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 5000L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("INSUFFICIENT_FUNDS"));

        assertWalletBalance(alice.apiKey(), alice.walletId(), 1000L);
        assertWalletBalance(bob.apiKey(), bob.walletId(), 0L);

        // Fund Alice, then retry with the exact same Idempotency-Key -> must succeed (FR-2.8)
        fundWallet(alice.walletId(), "INR", 5000L);
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 5000L)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.amountMinor").value(5000));
    }

    @Test
    void validationAuthAndOwnershipFailuresOnTransfer() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 10000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);
        UserAndWallet bobUsd = createUserWithFundedWallet("USD", 0L);

        // Missing API key -> 401
        mockMvc.perform(post("/transfers")
                        .header("Idempotency-Key", "k1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 100L)))
                .andExpect(status().isUnauthorized());

        // Caller does not own source wallet -> 403
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", bob.apiKey())
                        .header("Idempotency-Key", "k2")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 100L)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // Invalid amount -> 400
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", "k3")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 0L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("INVALID_AMOUNT"));

        // Same source and destination -> 400
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", "k4")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), alice.walletId(), "INR", 100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        // Unsupported currency -> 400
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", "k5")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "EUR", 100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("UNSUPPORTED_CURRENCY"));

        // Currency mismatch between wallets -> 400
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", "k6")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bobUsd.walletId(), "INR", 100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("CURRENCY_MISMATCH"));

        // Invalid idempotency keys (missing, >64 chars, non-ASCII) -> 400
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", "a".repeat(65))
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));

        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", "bad-\u00e9")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 100L)))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("VALIDATION_ERROR"));
    }

    @Test
    void idempotencyReplayConflictCaseSensitivityAndUserScoping() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 10000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 10000L);
        String key = "CaseKey-" + UUID.randomUUID();

        MvcResult first = mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 1000L)))
                .andExpect(status().isCreated())
                .andReturn();
        String firstTransferId = objectMapper.readTree(first.getResponse().getContentAsString())
                .get("transferId").asText();

        // Same key + same body -> 200 replay with Idempotent-Replayed: true
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 1000L)))
                .andExpect(status().isOk())
                .andExpect(header().string("Idempotent-Replayed", "true"))
                .andExpect(jsonPath("$.transferId").value(firstTransferId));

        // Same key + different body -> 409 IDEMPOTENCY_CONFLICT
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 2000L)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.error.code").value("IDEMPOTENCY_CONFLICT"));

        // Case-sensitive: lowercase variant is a distinct key
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", key.toLowerCase())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 500L)))
                .andExpect(status().isCreated());

        // Scoped per user: Bob using the exact same key succeeds as a new transfer
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", bob.apiKey())
                        .header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(bob.walletId(), alice.walletId(), "INR", 500L)))
                .andExpect(status().isCreated());

        assertWalletBalance(alice.apiKey(), alice.walletId(), 9000L); // -1000 - 500 + 500
        assertWalletBalance(bob.apiKey(), bob.walletId(), 11000L);  // +1000 + 500 - 500
    }

    @Test
    void concurrentDuplicateRequestsCreateOnlyOneTransfer() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 10000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);
        String key = "dup-concurrent-" + UUID.randomUUID();
        String body = transferBody(alice.walletId(), bob.walletId(), "INR", 3000L);

        int threads = 4;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Callable<MvcResult>> tasks = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            tasks.add(() -> {
                ready.countDown();
                go.await();
                return mockMvc.perform(post("/transfers")
                                .header("X-User-API-Key", alice.apiKey())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                        .andReturn();
            });
        }
        List<Future<MvcResult>> futures = new ArrayList<>();
        for (Callable<MvcResult> task : tasks) {
            futures.add(pool.submit(task));
        }
        ready.await();
        go.countDown();

        int createdCount = 0;
        int replayCount = 0;
        Set<String> transferIds = new HashSet<>();
        for (Future<MvcResult> future : futures) {
            MvcResult res = future.get();
            int sc = res.getResponse().getStatus();
            if (sc == 201) {
                createdCount++;
            } else if (sc == 200) {
                assertEquals("true", res.getResponse().getHeader("Idempotent-Replayed"));
                replayCount++;
            } else {
                throw new AssertionError("Unexpected status: " + sc + " body=" + res.getResponse().getContentAsString());
            }
            transferIds.add(objectMapper.readTree(res.getResponse().getContentAsString()).get("transferId").asText());
        }
        pool.shutdown();

        assertEquals(1, createdCount);
        assertEquals(threads - 1, replayCount);
        assertEquals(1, transferIds.size());
        assertWalletBalance(alice.apiKey(), alice.walletId(), 7000L);
        assertWalletBalance(bob.apiKey(), bob.walletId(), 3000L);
    }

    @Test
    void concurrentTransfersPreventOverdrafts() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 10000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);

        int threads = 5;
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<MvcResult>> futures = new ArrayList<>();

        for (int i = 0; i < threads; i++) {
            final String key = "race-" + i + "-" + UUID.randomUUID();
            futures.add(pool.submit(() -> {
                ready.countDown();
                go.await();
                return mockMvc.perform(post("/transfers")
                                .header("X-User-API-Key", alice.apiKey())
                                .header("Idempotency-Key", key)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(transferBody(alice.walletId(), bob.walletId(), "INR", 4000L)))
                        .andReturn();
            }));
        }
        ready.await();
        go.countDown();

        int succeeded = 0;
        int insufficient = 0;
        for (Future<MvcResult> future : futures) {
            int sc = future.get().getResponse().getStatus();
            if (sc == 201) {
                succeeded++;
            } else if (sc == 409) {
                insufficient++;
            } else {
                throw new AssertionError("Unexpected status: " + sc);
            }
        }
        pool.shutdown();

        assertEquals(2, succeeded);
        assertEquals(3, insufficient);
        assertWalletBalance(alice.apiKey(), alice.walletId(), 2000L);
        assertWalletBalance(bob.apiKey(), bob.walletId(), 8000L);
    }

    @Test
    void transferRetrievalEnforcesOwnership() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 5000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);
        UserAndWallet charlie = createUserWithFundedWallet("INR", 0L);

        MvcResult res = mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", alice.apiKey())
                        .header("Idempotency-Key", "get-test-" + UUID.randomUUID())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(transferBody(alice.walletId(), bob.walletId(), "INR", 1500L)))
                .andExpect(status().isCreated())
                .andReturn();
        String transferId = objectMapper.readTree(res.getResponse().getContentAsString())
                .get("transferId").asText();

        // Source owner allowed
        mockMvc.perform(get("/transfers/" + transferId)
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(transferId));

        // Destination owner allowed
        mockMvc.perform(get("/transfers/" + transferId)
                        .header("X-User-API-Key", bob.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transferId").value(transferId));

        // Unrelated user forbidden
        mockMvc.perform(get("/transfers/" + transferId)
                        .header("X-User-API-Key", charlie.apiKey()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.error.code").value("FORBIDDEN"));

        // Unknown transfer -> 404
        mockMvc.perform(get("/transfers/" + UUID.randomUUID())
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("TRANSFER_NOT_FOUND"));
    }

    @Test
    void transferWriteRollbackLeavesNoPartialChanges() throws Exception {
        UserAndWallet alice = createUserWithFundedWallet("INR", 5000L);
        UserAndWallet bob = createUserWithFundedWallet("INR", 0L);
        String txTransferId = UUID.randomUUID().toString();

        assertThrows(RuntimeException.class, () -> txExecutor.executeWrite(conn -> {
            transferRepository.insert(conn, new TransferRecord(
                    txTransferId, alice.walletId(), bob.walletId(), "INR", 1000L,
                    "COMPLETED", "2026-11-26T00:00:00Z"));
            throw new RuntimeException("Simulated mid-transfer failure");
        }));

        mockMvc.perform(get("/transfers/" + txTransferId)
                        .header("X-User-API-Key", alice.apiKey()))
                .andExpect(status().isNotFound());
        assertWalletBalance(alice.apiKey(), alice.walletId(), 5000L);
        assertWalletBalance(bob.apiKey(), bob.walletId(), 0L);
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
            fundWallet(walletId, currency, amountMinor);
        }
        return new UserAndWallet(userId, apiKey, walletId);
    }

    private void fundWallet(String walletId, String currency, long amountMinor) throws Exception {
        mockMvc.perform(post("/admin/funding")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletId\":\"" + walletId + "\",\"amountMinor\":" + amountMinor
                                + ",\"currencyCode\":\"" + currency + "\"}"))
                .andExpect(status().isOk());
    }

    private void assertWalletBalance(String apiKey, String walletId, long expected) throws Exception {
        mockMvc.perform(get("/wallets/" + walletId)
                        .header("X-User-API-Key", apiKey))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(expected));
    }

    private static String transferBody(String src, String dst, String currency, long amount) {
        return "{\"sourceWalletId\":\"" + src + "\",\"destinationWalletId\":\"" + dst
                + "\",\"currencyCode\":\"" + currency + "\",\"amountMinor\":" + amount + "}";
    }
}
