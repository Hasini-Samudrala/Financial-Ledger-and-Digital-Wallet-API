package com.finledger.api.config;

import jakarta.annotation.PostConstruct;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Externalized application configuration bound from {@code app.*}.
 */
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    private List<String> supportedCurrencies = List.of("INR", "USD");
    private Map<String, CurrencyConfig> currency = new HashMap<>();
    private String adminApiKey;
    private String ledgerHmacSecret;
    private SqliteConfigProperties sqlite = new SqliteConfigProperties();

    private byte[] decodedHmacSecret;

    @PostConstruct
    void validate() {
        if (ledgerHmacSecret == null || ledgerHmacSecret.isBlank()) {
            throw new IllegalStateException(
                    "LEDGER_HMAC_SECRET (app.ledger-hmac-secret) must be configured and at least 32 bytes.");
        }
        try {
            decodedHmacSecret = Base64.getDecoder().decode(ledgerHmacSecret.trim());
        } catch (IllegalArgumentException ex) {
            throw new IllegalStateException("LEDGER_HMAC_SECRET must be valid Base64.", ex);
        }
        if (decodedHmacSecret.length < 32) {
            throw new IllegalStateException(
                    "LEDGER_HMAC_SECRET must decode to at least 32 bytes (256 bits).");
        }
    }

    public List<String> getSupportedCurrencies() {
        return supportedCurrencies;
    }

    public void setSupportedCurrencies(List<String> supportedCurrencies) {
        this.supportedCurrencies = supportedCurrencies;
    }

    public Map<String, CurrencyConfig> getCurrency() {
        return currency;
    }

    public void setCurrency(Map<String, CurrencyConfig> currency) {
        this.currency = currency;
    }

    public String getAdminApiKey() {
        return adminApiKey;
    }

    public void setAdminApiKey(String adminApiKey) {
        this.adminApiKey = adminApiKey;
    }

    public String getLedgerHmacSecret() {
        return ledgerHmacSecret;
    }

    public void setLedgerHmacSecret(String ledgerHmacSecret) {
        this.ledgerHmacSecret = ledgerHmacSecret;
    }

    public byte[] getDecodedHmacSecret() {
        return decodedHmacSecret.clone();
    }

    public SqliteConfigProperties getSqlite() {
        return sqlite;
    }

    public void setSqlite(SqliteConfigProperties sqlite) {
        this.sqlite = sqlite;
    }

    public static class CurrencyConfig {
        private int scale = 2;

        public int getScale() {
            return scale;
        }

        public void setScale(int scale) {
            this.scale = scale;
        }
    }

    public static class SqliteConfigProperties {
        private int busyTimeoutMs = 5000;
        private int retryCount = 3;
        private long retryWaitMs = 50;

        public int getBusyTimeoutMs() {
            return busyTimeoutMs;
        }

        public void setBusyTimeoutMs(int busyTimeoutMs) {
            this.busyTimeoutMs = busyTimeoutMs;
        }

        public int getRetryCount() {
            return retryCount;
        }

        public void setRetryCount(int retryCount) {
            this.retryCount = retryCount;
        }

        public long getRetryWaitMs() {
            return retryWaitMs;
        }

        public void setRetryWaitMs(long retryWaitMs) {
            this.retryWaitMs = retryWaitMs;
        }
    }
}
