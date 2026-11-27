package com.finledger.api;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
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
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class AdminIntegrityAndReconciliationTest {

    private static final String ADMIN_KEY = "test-admin-secret-key-for-junit";

    @TempDir
    static Path tempDir;

    @DynamicPropertySource
    static void registerTempDb(DynamicPropertyRegistry registry) {
        String dbFile = tempDir.resolve("admin_verify_" + UUID.randomUUID() + ".db").toString();
        registry.add("spring.datasource.url", () -> "jdbc:sqlite:" + dbFile);
    }

    @Autowired
    private WebApplicationContext context;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private DataSource dataSource;

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.webAppContextSetup(context)
                .apply(springSecurity())
                .build();
    }

    @Test
    void adminAuthenticationEnforcedOnIntegrityAndReconciliation() throws Exception {
        mockMvc.perform(get("/admin/ledger-integrity"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"));

        mockMvc.perform(get("/admin/ledger-integrity")
                        .header("X-Admin-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/admin/reconciliation"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.error.code").value("INVALID_API_KEY"));

        mockMvc.perform(get("/admin/reconciliation")
                        .header("X-Admin-API-Key", "wrong-key"))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void emptyLedgerPassesIntegrityAndReconciliationAndIsReadOnly() throws Exception {
        mockMvc.perform(get("/admin/ledger-integrity")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.totalEntries").value(0))
                .andExpect(jsonPath("$.lastSequence").value(0))
                .andExpect(jsonPath("$.chainValid").value(true))
                .andExpect(jsonPath("$.checkpointValid").value(true))
                .andExpect(jsonPath("$.earliestMismatchSequence").doesNotExist());

        mockMvc.perform(get("/admin/reconciliation")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.currencyTotals").isEmpty())
                .andExpect(jsonPath("$.unbalancedTransactions").isEmpty())
                .andExpect(jsonPath("$.walletDiscrepancies").isEmpty())
                .andExpect(jsonPath("$.walletCount").value(0));
    }

    @Test
    void validMultiCurrencyLedgerPassesIntegrityAndReconciliation() throws Exception {
        UserWalletPair aliceInr = createFundedWallet("alice_mc", "INR", 10000L);
        UserWalletPair bobInr = createFundedWallet("bob_mc", "INR", 2000L);
        UserWalletPair aliceUsd = createWalletForExisting(aliceInr, "USD");
        fundWallet(aliceUsd.walletId(), "USD", 5000L);

        // Perform INR transfer
        mockMvc.perform(post("/transfers")
                        .header("X-User-API-Key", aliceInr.apiKey())
                        .header("Idempotency-Key", "mc-tx-1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"sourceWalletId\":\"" + aliceInr.walletId()
                                + "\",\"destinationWalletId\":\"" + bobInr.walletId()
                                + "\",\"currencyCode\":\"INR\",\"amountMinor\":3000}"))
                .andExpect(status().isCreated());

        long beforeCount = countLedgerEntries();

        mockMvc.perform(get("/admin/ledger-integrity")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.totalEntries").value(8))
                .andExpect(jsonPath("$.lastSequence").value(8))
                .andExpect(jsonPath("$.chainValid").value(true))
                .andExpect(jsonPath("$.checkpointValid").value(true));

        mockMvc.perform(get("/admin/reconciliation")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PASS"))
                .andExpect(jsonPath("$.currencyTotals.length()").value(2))
                .andExpect(jsonPath("$.currencyTotals[0].currencyCode").value("INR"))
                .andExpect(jsonPath("$.currencyTotals[0].totalDebitsMinor").value(15000))
                .andExpect(jsonPath("$.currencyTotals[0].totalCreditsMinor").value(15000))
                .andExpect(jsonPath("$.currencyTotals[0].balanced").value(true))
                .andExpect(jsonPath("$.currencyTotals[1].currencyCode").value("USD"))
                .andExpect(jsonPath("$.currencyTotals[1].totalDebitsMinor").value(5000))
                .andExpect(jsonPath("$.currencyTotals[1].totalCreditsMinor").value(5000))
                .andExpect(jsonPath("$.currencyTotals[1].balanced").value(true))
                .andExpect(jsonPath("$.unbalancedTransactions").isEmpty())
                .andExpect(jsonPath("$.walletDiscrepancies").isEmpty())
                .andExpect(jsonPath("$.walletCount").value(3))
                .andExpect(jsonPath("$.matchedWalletCount").value(3));

        // Both endpoints are strictly read-only
        assertEquals(beforeCount, countLedgerEntries());
    }

    @Test
    void integrityDetectsEntryTamperingBrokenPreviousHashAndInvalidCheckpoint() throws Exception {
        createFundedWallet("tamper_u", "INR", 10000L);

        // 1. Tamper entry_hash at sequence 2 (simulate offline DB edit)
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER prevent_ledger_entry_update");
            st.execute("UPDATE ledger_entries SET entry_hash = zeroblob(32) WHERE sequence_no = 2");
        }
        mockMvc.perform(get("/admin/ledger-integrity")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.chainValid").value(false))
                .andExpect(jsonPath("$.earliestMismatchSequence").value(2));

        // 2. Tamper previous_hash at sequence 1 -> earliest mismatch becomes 1
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("UPDATE ledger_entries SET previous_hash = randomblob(32) WHERE sequence_no = 1");
        }
        mockMvc.perform(get("/admin/ledger-integrity")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.chainValid").value(false))
                .andExpect(jsonPath("$.earliestMismatchSequence").value(1));
    }

    @Test
    void integrityDetectsInvalidCheckpointMac() throws Exception {
        createFundedWallet("cp_u", "INR", 5000L);

        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("UPDATE integrity_checkpoints SET checkpoint_mac = zeroblob(32) WHERE checkpoint_id = 1");
        }

        mockMvc.perform(get("/admin/ledger-integrity")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.chainValid").value(true))
                .andExpect(jsonPath("$.checkpointValid").value(false));
    }

    @Test
    void reconciliationDetectsStoredBalanceMismatchAndDebitCreditMismatchWithoutModifyingData() throws Exception {
        UserWalletPair user = createFundedWallet("recon_u", "INR", 10000L);

        // Artificially corrupt stored_balance_minor and debit_minor in disposable DB
        try (Connection conn = dataSource.getConnection(); Statement st = conn.createStatement()) {
            st.execute("DROP TRIGGER prevent_ledger_entry_update");
            st.execute("UPDATE wallets SET stored_balance_minor = 12345 WHERE wallet_id = '" + user.walletId() + "'");
            st.execute("UPDATE ledger_entries SET debit_minor = 9000 WHERE sequence_no = 1");
        }

        mockMvc.perform(get("/admin/reconciliation")
                        .header("X-Admin-API-Key", ADMIN_KEY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("FAIL"))
                .andExpect(jsonPath("$.currencyTotals[0].balanced").value(false))
                .andExpect(jsonPath("$.unbalancedTransactions.length()").value(1))
                .andExpect(jsonPath("$.walletDiscrepancies.length()").value(1))
                .andExpect(jsonPath("$.walletDiscrepancies[0].walletId").value(user.walletId()))
                .andExpect(jsonPath("$.walletDiscrepancies[0].storedBalanceMinor").value(12345))
                .andExpect(jsonPath("$.walletDiscrepancies[0].ledgerBalanceMinor").value(10000))
                .andExpect(jsonPath("$.walletDiscrepancies[0].differenceMinor").value(2345));

        // Confirm reconciliation did NOT repair or modify stored_balance_minor
        mockMvc.perform(get("/wallets/" + user.walletId())
                        .header("X-User-API-Key", user.apiKey()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.balanceMinor").value(12345));
    }

    private record UserWalletPair(String username, String apiKey, String walletId) {
    }

    private UserWalletPair createFundedWallet(String username, String currency, long amountMinor) throws Exception {
        MvcResult reg = mockMvc.perform(post("/users")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        JsonNode userJson = objectMapper.readTree(reg.getResponse().getContentAsString());
        String apiKey = userJson.get("apiKey").asText();

        UserWalletPair pair = createWalletForExisting(new UserWalletPair(username, apiKey, null), currency);
        if (amountMinor > 0) {
            fundWallet(pair.walletId(), currency, amountMinor);
        }
        return pair;
    }

    private UserWalletPair createWalletForExisting(UserWalletPair owner, String currency) throws Exception {
        MvcResult w = mockMvc.perform(post("/users/" + owner.username() + "/wallets")
                        .header("X-User-API-Key", owner.apiKey())
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"currencyCode\":\"" + currency + "\"}"))
                .andExpect(status().isCreated())
                .andReturn();
        String walletId = objectMapper.readTree(w.getResponse().getContentAsString()).get("walletId").asText();
        return new UserWalletPair(owner.username(), owner.apiKey(), walletId);
    }

    private void fundWallet(String walletId, String currency, long amountMinor) throws Exception {
        mockMvc.perform(post("/admin/funding")
                        .header("X-Admin-API-Key", ADMIN_KEY)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"walletId\":\"" + walletId + "\",\"amountMinor\":" + amountMinor
                                + ",\"currencyCode\":\"" + currency + "\"}"))
                .andExpect(status().isOk());
    }

    private long countLedgerEntries() throws Exception {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("SELECT COUNT(*) FROM ledger_entries");
             ResultSet rs = ps.executeQuery()) {
            rs.next();
            return rs.getLong(1);
        }
    }
}
