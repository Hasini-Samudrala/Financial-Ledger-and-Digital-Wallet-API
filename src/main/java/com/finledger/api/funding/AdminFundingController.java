package com.finledger.api.funding;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only funding endpoint ({@code POST /admin/funding}).
 */
@RestController
@RequestMapping("/admin/funding")
public class AdminFundingController {

    public record FundingRequest(String walletId, Long amountMinor, String currencyCode) {
    }

    private final FundingService fundingService;

    public AdminFundingController(FundingService fundingService) {
        this.fundingService = fundingService;
    }

    @PostMapping
    public ResponseEntity<FundingService.FundingResult> fundWallet(@RequestBody FundingRequest request) {
        FundingService.FundingResult result = fundingService.fundWallet(
                request.walletId(), request.amountMinor(), request.currencyCode());
        return ResponseEntity.ok(result);
    }
}
