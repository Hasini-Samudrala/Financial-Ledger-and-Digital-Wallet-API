package com.finledger.api.transfer;

import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Transfer endpoints: {@code POST /transfers} and {@code GET /transfers/{transferId}}.
 */
@RestController
@RequestMapping("/transfers")
public class TransferController {

    public record CreateTransferRequest(
            String sourceWalletId,
            String destinationWalletId,
            String currencyCode,
            Long amountMinor
    ) {
    }

    private final TransferService transferService;

    public TransferController(TransferService transferService) {
        this.transferService = transferService;
    }

    @PostMapping
    public ResponseEntity<TransferRecord> createTransfer(
            @RequestHeader(value = "Idempotency-Key", required = false) String idempotencyKey,
            @RequestBody CreateTransferRequest request,
            Authentication authentication) {
        TransferService.TransferExecutionResult result = transferService.createTransfer(
                authentication.getName(),
                idempotencyKey,
                request.sourceWalletId(),
                request.destinationWalletId(),
                request.currencyCode(),
                request.amountMinor());

        if (result.replayed()) {
            HttpHeaders headers = new HttpHeaders();
            headers.set("Idempotent-Replayed", "true");
            return new ResponseEntity<>(result.transfer(), headers, HttpStatus.OK);
        }
        return ResponseEntity.status(HttpStatus.CREATED).body(result.transfer());
    }

    @GetMapping("/{transferId}")
    public ResponseEntity<TransferRecord> getTransfer(
            @PathVariable String transferId,
            Authentication authentication) {
        return ResponseEntity.ok(transferService.getTransfer(authentication.getName(), transferId));
    }

    @PostMapping("/{transferId}/reversal")
    public ResponseEntity<TransferRecord> reverseTransfer(
            @PathVariable String transferId,
            Authentication authentication) {
        TransferRecord reversal = transferService.reverseTransfer(authentication.getName(), transferId);
        return ResponseEntity.status(HttpStatus.CREATED).body(reversal);
    }
}
