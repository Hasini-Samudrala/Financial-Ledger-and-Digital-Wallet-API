package com.finledger.api.wallet;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Wallet endpoints: username-based creation/lookup and UUID-based retrieval.
 */
@RestController
public class WalletController {

    public record CreateWalletRequest(@NotBlank String currencyCode) {
    }

    private final WalletService walletService;

    public WalletController(WalletService walletService) {
        this.walletService = walletService;
    }

    @PostMapping("/users/{username}/wallets")
    public ResponseEntity<WalletRecord> createWallet(
            @PathVariable String username,
            @Valid @RequestBody CreateWalletRequest request,
            Authentication authentication) {
        WalletRecord created = walletService.createWalletByUsername(
                authentication.getName(), username, request.currencyCode());
        return ResponseEntity.status(HttpStatus.CREATED).body(created);
    }

    @GetMapping("/users/{username}/wallets/{currencyCode}")
    public ResponseEntity<WalletRecord> getWalletByUsernameAndCurrency(
            @PathVariable String username,
            @PathVariable String currencyCode,
            Authentication authentication) {
        WalletRecord wallet = walletService.getWalletByUsernameAndCurrency(
                authentication.getName(), username, currencyCode);
        return ResponseEntity.ok(wallet);
    }

    @GetMapping("/wallets/{walletId}")
    public ResponseEntity<WalletRecord> getWallet(
            @PathVariable String walletId,
            Authentication authentication) {
        WalletRecord wallet = walletService.getWallet(authentication.getName(), walletId);
        return ResponseEntity.ok(wallet);
    }

    @GetMapping("/wallets/{walletId}/ledger-entries")
    public ResponseEntity<WalletService.LedgerHistoryResponse> getLedgerHistory(
            @PathVariable String walletId,
            @RequestParam(required = false) Long cursor,
            @RequestParam(required = false) Integer limit,
            Authentication authentication) {
        return ResponseEntity.ok(walletService.getLedgerHistory(
                authentication.getName(), walletId, cursor, limit));
    }
}
