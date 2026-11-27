package com.finledger.api.ledger;

/**
 * Unchained ledger entry input before sequence assignment and hashing.
 */
public record LedgerEntryDraft(
        String entryId,
        String accountId,
        String currencyCode,
        long debitMinor,
        long creditMinor
) {
}
