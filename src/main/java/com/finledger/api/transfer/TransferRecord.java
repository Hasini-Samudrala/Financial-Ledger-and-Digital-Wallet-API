package com.finledger.api.transfer;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Transfer entity and API response representation (ADR-006).
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public record TransferRecord(
        String transferId,
        String sourceWalletId,
        String destinationWalletId,
        String currencyCode,
        long amountMinor,
        String status,
        String reversalOf,
        String createdAt
) {
    public TransferRecord(
            String transferId,
            String sourceWalletId,
            String destinationWalletId,
            String currencyCode,
            long amountMinor,
            String status,
            String createdAt) {
        this(transferId, sourceWalletId, destinationWalletId, currencyCode, amountMinor, status, null, createdAt);
    }
}
