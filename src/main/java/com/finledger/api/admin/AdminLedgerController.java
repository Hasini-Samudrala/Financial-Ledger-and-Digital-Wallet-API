package com.finledger.api.admin;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * Admin-only ledger integrity and reconciliation endpoints.
 */
@RestController
@RequestMapping("/admin")
public class AdminLedgerController {

    private final AdminLedgerService adminLedgerService;

    public AdminLedgerController(AdminLedgerService adminLedgerService) {
        this.adminLedgerService = adminLedgerService;
    }

    @GetMapping("/ledger-integrity")
    public ResponseEntity<AdminLedgerService.LedgerIntegrityReport> verifyIntegrity() {
        return ResponseEntity.ok(adminLedgerService.verifyIntegrity());
    }

    @GetMapping("/reconciliation")
    public ResponseEntity<AdminLedgerService.ReconciliationReport> reconcile() {
        return ResponseEntity.ok(adminLedgerService.reconcile());
    }
}
