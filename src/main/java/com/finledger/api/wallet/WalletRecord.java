package com.finledger.api.wallet;

/**
 * Wallet entity mapped from the {@code wallets} table.
 */
public record WalletRecord(
        String walletId,
        String userId,
        String currencyCode,
        long balanceMinor
) {
}
