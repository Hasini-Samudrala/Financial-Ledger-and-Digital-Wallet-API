package com.finledger.api.config;

import org.springframework.stereotype.Component;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Registry of supported ISO 4217 currency codes and their minor-unit scales (ADR-005).
 */
@Component
public class SupportedCurrencies {

    private final Map<String, Integer> scales;

    public SupportedCurrencies(AppProperties properties) {
        Map<String, Integer> map = new LinkedHashMap<>();
        for (String code : properties.getSupportedCurrencies()) {
            String normalized = code.trim().toUpperCase();
            AppProperties.CurrencyConfig cfg = properties.getCurrency().get(normalized);
            int scale = (cfg != null) ? cfg.getScale() : 2;
            map.put(normalized, scale);
        }
        this.scales = Collections.unmodifiableMap(map);
    }

    public boolean isSupported(String currencyCode) {
        return currencyCode != null && scales.containsKey(currencyCode);
    }

    public int getScale(String currencyCode) {
        Integer scale = scales.get(currencyCode);
        if (scale == null) {
            throw new IllegalArgumentException("Unsupported currency: " + currencyCode);
        }
        return scale;
    }

    public Set<String> codes() {
        return scales.keySet();
    }
}
