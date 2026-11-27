# Requirement Traceability Matrix

- **Document:** `docs/traceability-matrix.md`
- **Source baseline:** SRS v1.3 (`brain/srs/Financial_Ledger_Digital_Wallet_SRS_v1.3.pdf` — authoritative; `v1.2` is historical)
- **Date:** 2026-11-26
- **Status:** Stages 1–6 Implemented & Tested Locally; Stage 7 local deliverables complete; GitHub Issues/PRs/CI pending team setup.

---

## Functional Requirements

| Req ID | Requirement summary | Design / ADR | Implementation | Tests | Skill | Supporting Brain Note | GitHub Issue / PR | Status |
|---|---|---|---|---|---|---|---|---|
| FR-1.1 | Register user with unique username; return userId, username, and API key once | user-wallet-design §4; ADR-002; ADR-006 | `UserController`, `UserService`, `ApiKeyService`, `UserRepository` | `ApiMilestoneIntegrationTest.userRegistrationValidationDuplicateAndAuthenticationEnforced` | `simulate-transfer` | [[srs-requirements-notes]] | Pending team/GitHub setup | Tested locally |
| FR-1.2 | One wallet per user per supported currency (username route) | user-wallet-design §6; database-ledger-design §4.2; ADR-005 | `WalletController`, `WalletService`, `WalletRepository`, `SupportedCurrencies` | `ApiMilestoneIntegrationTest.walletCreationAndLookupByUsernameAndUuidWithOwnershipAndValidation` | `simulate-transfer` | [[monetary-precision]] | Pending team/GitHub setup | Tested locally |
| FR-1.3 | New wallet starts at zero; balance only via ledger; lookup by username+currency or walletId | user-wallet-design §6.6, §7; database-ledger-design §4.2 | `WalletService`, `WalletRepository` | `ApiMilestoneIntegrationTest.walletCreationAndLookupByUsernameAndUuidWithOwnershipAndValidation` | `simulate-transfer` | [[double-entry-accounting]] | Pending team/GitHub setup | Tested locally |
| FR-1.4 | Admin funding: debit system account, credit wallet | database-ledger-design §8.3; architecture §6.3; ADR-005 | `AdminFundingController`, `FundingService`, `LedgerRepository` | `ApiMilestoneIntegrationTest.adminFundingUpdatesBalanceDoubleEntryLedgerHashChainAndCheckpoint` | `simulate-transfer` | [[double-entry-accounting]] | Pending team/GitHub setup | Tested locally |
| FR-1.5 | UUID identifiers for all internal resources | user-wallet-design §10.1 | `UserService`, `WalletService`, `FundingService`, `TransferService` | `ApiMilestoneIntegrationTest.walletCreationAndLookupByUsernameAndUuidWithOwnershipAndValidation` | — | [[srs-requirements-notes]] | Pending team/GitHub setup | Tested locally |
| FR-2.1 | Positive, same-currency transfer; source ≠ destination | transfer-and-concurrency-design §3.3 | `TransferController`, `TransferService` | `TransferAndConcurrencyIntegrationTest.successfulTransferUpdatesBalancesLedgerHashChainAndCheckpoint`, `validationAuthAndOwnershipFailuresOnTransfer` | `simulate-transfer` | [[monetary-precision]] | Pending team/GitHub setup | Tested locally |
| FR-2.2 | Every transfer requires `Idempotency-Key` (1–64 ASCII chars) | transfer-and-concurrency-design §3.4; ADR-006 | `TransferService`, `IdempotencyRepository` | `TransferAndConcurrencyIntegrationTest.validationAuthAndOwnershipFailuresOnTransfer` | `simulate-transfer` | [[api-idempotency-keys]] | Pending team/GitHub setup | Tested locally |
| FR-2.3 | Debit source and equal credit to destination | transfer-and-concurrency-design §3.4; database-ledger-design §8 | `TransferService`, `LedgerRepository` | `TransferAndConcurrencyIntegrationTest.successfulTransferUpdatesBalancesLedgerHashChainAndCheckpoint` | `simulate-transfer` | [[double-entry-accounting]] | Pending team/GitHub setup | Tested locally |
| FR-2.4 | Transfer, entries, idempotency, balances, checkpoint commit atomically | transfer-and-concurrency-design §3.4; database-ledger-design §11.4 | `TransferService`, `SqliteTransactionExecutor` | `TransferAndConcurrencyIntegrationTest.successfulTransferUpdatesBalancesLedgerHashChainAndCheckpoint`, `TransferWorkflowRollbackAndUnitTest.realTransferWorkflowRollbackLeavesZeroPartialState` | — | [[sqlite-acid-transactions-final]], [[integration-testing-and-rollback]] | Pending team/GitHub setup | Tested locally |
| FR-2.5 | Keys scoped per user; replay (`Idempotent-Replayed: true`); conflict → 409 | transfer-and-concurrency-design §3.4; ADR-006 | `TransferService`, `TransferController`, `IdempotencyRepository` | `TransferAndConcurrencyIntegrationTest.idempotencyReplayConflictCaseSensitivityAndUserScoping` | `simulate-transfer` | [[api-idempotency-keys]] | Pending team/GitHub setup | Tested locally |
| FR-2.6 | Concurrent same-key requests create at most one transfer | transfer-and-concurrency-design §5; ADR-003 | `TransferService`, `SqliteTransactionExecutor` | `TransferAndConcurrencyIntegrationTest.concurrentDuplicateRequestsCreateOnlyOneTransfer` | `simulate-transfer` | [[api-idempotency-keys]], [[race-conditions]] | Pending team/GitHub setup | Tested locally |
| FR-2.7 | Failed attempts not cached as successes; fingerprint covers user, wallets, amount, currency | transfer-and-concurrency-design §3.4, §3.5 | `TransferService`, `SqliteTransactionExecutor` | `TransferAndConcurrencyIntegrationTest.insufficientFundsReturns409AndIsNotCachedAsIdempotentSuccess`, `TransferWorkflowRollbackAndUnitTest` | — | [[api-idempotency-keys]] | Pending team/GitHub setup | Tested locally |
| FR-2.8 | Positive integer minor units valid for currency precision | ADR-005; SRS §6.1 | `TransferService`, `SupportedCurrencies` | `TransferAndConcurrencyIntegrationTest.validationAuthAndOwnershipFailuresOnTransfer` | — | [[monetary-precision]] | Pending team/GitHub setup | Tested locally |
| FR-2.9 | Valid user key and source-wallet ownership checked (401/403) | user-wallet-design §9; transfer-and-concurrency-design §3.3 | `ApiKeyAuthFilter`, `TransferService` | `TransferAndConcurrencyIntegrationTest.validationAuthAndOwnershipFailuresOnTransfer`, `transferRetrievalEnforcesOwnership` | — | [[srs-requirements-notes]] | Pending team/GitHub setup | Tested locally |
| FR-3.1 | Concurrent transfers do not cause overspending | transfer-and-concurrency-design §5; ADR-003 | `SqliteTransactionExecutor`, `TransferService` | `TransferAndConcurrencyIntegrationTest.concurrentTransfersPreventOverdrafts` | `simulate-transfer` | [[race-conditions]] | Pending team/GitHub setup | Tested locally |
| FR-3.2 | Write lock acquired before balance check | transfer-and-concurrency-design §5.1; ADR-003 | `SqliteTransactionExecutor` (`BEGIN IMMEDIATE`), `TransferService` | `TransferAndConcurrencyIntegrationTest.concurrentTransfersPreventOverdrafts` | `simulate-transfer` | [[sqlite-wal-mode]], [[spring-transactional]] | Pending team/GitHub setup | Tested locally |
| FR-3.3 | Failed competing requests roll back completely | transfer-and-concurrency-design §5.1; ADR-003 | `SqliteTransactionExecutor`, `TransferService` | `TransferAndConcurrencyIntegrationTest.concurrentTransfersPreventOverdrafts` | `simulate-transfer` | [[sqlite-acid-transactions-final]] | Pending team/GitHub setup | Tested locally |
| FR-3.4 | `SQLITE_BUSY` handled without partial writes | database-ledger-design §13; ADR-003 | `SqliteTransactionExecutor`, `GlobalExceptionHandler` | `TransferWorkflowRollbackAndUnitTest.databaseBusyMapsTo503WithRetryAfterHeader` | — | [[sqlite-wal-mode]] | Pending team/GitHub setup | Tested locally |
| FR-3.5 | Automated concurrency tests on separate connections | development-plan Stage 4; ADR-003 | `TransferAndConcurrencyIntegrationTest` | `TransferAndConcurrencyIntegrationTest.concurrentTransfersPreventOverdrafts`, `concurrentDuplicateRequestsCreateOnlyOneTransfer` | `simulate-transfer` | [[integration-testing-and-rollback]] | Pending team/GitHub setup | Tested locally |
| FR-4.1 | Committed ledger entries cannot be updated or deleted | database-ledger-design §9; user-wallet-design §8.6 | `schema.sql` immutability triggers | `DatabaseAndLedgerCoreTest.immutabilityTriggersRejectUpdateAndDeleteOnLedgerTables` | — | [[immutable-audit-trails-and-reversals]] | Pending team/GitHub setup | Tested locally |
| FR-4.2 | Each entry stores sequence, tx, account, currency, debit/credit, hashes, UTC timestamp | database-ledger-design §4.7, §6.7; ADR-004 | `LedgerRepository`, `LedgerHashChainService` | `DatabaseAndLedgerCoreTest.canonicalHashChainAndCheckpointAreDeterministic`, `TransferWorkflowRollbackAndUnitTest` | — | [[immutable-audit-trails-and-reversals]] | Pending team/GitHub setup | Tested locally |
| FR-4.3 | Hash-chain integrity check; report earliest mismatch | database-ledger-design §11; ADR-004 | `AdminLedgerController.verifyIntegrity`, `AdminLedgerService.verifyIntegrity` | `AdminIntegrityAndReconciliationTest.validMultiCurrencyLedgerPassesIntegrityAndReconciliation`, `integrityDetectsEntryTamperingBrokenPreviousHashAndInvalidCheckpoint` | — | [[immutable-audit-trails-and-reversals]] | Pending team/GitHub setup | Tested locally |
| FR-4.4 | External-secret checkpoint detects chain replacement | database-ledger-design §11.3; ADR-004 | `AdminLedgerService.verifyIntegrity`, `LedgerHashChainService.computeCheckpointMac` | `AdminIntegrityAndReconciliationTest.integrityDetectsInvalidCheckpointMac` | — | [[immutable-audit-trails-and-reversals]] | Pending team/GitHub setup | Tested locally |
| FR-4.5 | Reversal is a new compensating transaction; original entries unchanged | transfer-and-concurrency-design §6; database-ledger-design §10 | `TransferController.reverseTransfer`, `TransferService.reverseTransfer`, `TransferRepository` | `ReversalAndLedgerHistoryIntegrationTest.successfulReversalCreatesCompensatingEntriesRestoresBalancesAndPreservesOriginalEntries` | — | [[immutable-audit-trails-and-reversals]] | Pending team/GitHub setup | Tested locally |
| FR-4.6 | At most one reversal per original transfer | transfer-and-concurrency-design §6; database-ledger-design §7.1 | `TransferService.reverseTransfer`, `uq_one_reversal_per_transfer` | `ReversalAndLedgerHistoryIntegrationTest.concurrentReversalAttemptsCreateOnlyOneReversal`, `successfulReversalCreatesCompensatingEntriesRestoresBalancesAndPreservesOriginalEntries` | — | [[immutable-audit-trails-and-reversals]] | Pending team/GitHub setup | Tested locally |
| FR-4.7 | Ledger history: chronological, pages of max 100 entries | user-wallet-design §8; ADR-006 | `WalletController.getLedgerHistory`, `WalletService.getLedgerHistory`, `LedgerRepository.findWalletEntriesAfterSequence` | `ReversalAndLedgerHistoryIntegrationTest.ledgerHistoryPaginationOrderingValidationOwnershipAndReversalIntegrity` | — | [[immutable-audit-trails-and-reversals]] | Pending team/GitHub setup | Tested locally |
| FR-5.1 | Reconciliation checks debit/credit totals per currency | database-ledger-design §12.1 | `AdminLedgerController.reconcile`, `AdminLedgerService.reconcile` | `AdminIntegrityAndReconciliationTest.validMultiCurrencyLedgerPassesIntegrityAndReconciliation`, `reconciliationDetectsStoredBalanceMismatchAndDebitCreditMismatchWithoutModifyingData` | `simulate-transfer` | [[ledger-reconciliation]] | Pending team/GitHub setup | Tested locally |
| FR-5.2 | Reconciliation checks each transaction individually | database-ledger-design §12.1 | `AdminLedgerService.reconcile` | `AdminIntegrityAndReconciliationTest.reconciliationDetectsStoredBalanceMismatchAndDebitCreditMismatchWithoutModifyingData` | — | [[ledger-reconciliation]] | Pending team/GitHub setup | Tested locally |
| FR-5.3 | Reconciliation checks stored vs ledger-derived wallet balances | database-ledger-design §12.2 | `AdminLedgerService.reconcile` | `AdminIntegrityAndReconciliationTest.validMultiCurrencyLedgerPassesIntegrityAndReconciliation`, `reconciliationDetectsStoredBalanceMismatchAndDebitCreditMismatchWithoutModifyingData` | `simulate-transfer` | [[ledger-reconciliation]] | Pending team/GitHub setup | Tested locally |
| FR-5.4 | Reconciliation reads use one consistent snapshot | database-ledger-design §12.3 | `SqliteTransactionExecutor.executeReadTransaction` (`BEGIN DEFERRED`) | `AdminIntegrityAndReconciliationTest.validMultiCurrencyLedgerPassesIntegrityAndReconciliation` | — | [[sqlite-acid-transactions-final]] | Pending team/GitHub setup | Tested locally |

---

## Non-Functional Requirements

| Req ID | Requirement summary | Design / ADR | Implementation | Tests | Skill | Supporting Brain Note | GitHub Issue / PR | Status |
|---|---|---|---|---|---|---|---|---|
| NFR-1 | Concurrent test scenarios run without corruption | SRS §5.1 | `TransferAndConcurrencyIntegrationTest` | `TransferAndConcurrencyIntegrationTest.concurrentTransfersPreventOverdrafts` | `simulate-transfer` | [[race-conditions]] | Pending team/GitHub setup | Tested locally |
| NFR-2 | 503 `Retry-After` on SQLite contention | transfer-and-concurrency-design §5.2; ADR-003 | `SqliteTransactionExecutor`, `DatabaseBusyException`, `GlobalExceptionHandler` | `TransferWorkflowRollbackAndUnitTest.databaseBusyMapsTo503WithRetryAfterHeader` | — | [[sqlite-wal-mode]] | Pending team/GitHub setup | Tested locally |
| NFR-3 | Zero/negative amounts and negative balances rejected | SRS §5.2 | `WalletService`, `FundingService`, `TransferService`, `schema.sql` CHECK constraints | `TransferAndConcurrencyIntegrationTest.validationAuthAndOwnershipFailuresOnTransfer`, `ApiMilestoneIntegrationTest` | — | [[monetary-precision]] | Pending team/GitHub setup | Tested locally |
| NFR-4 | Demonstration only; never holds real funds | SRS §5.2 | `README.md`, `AppProperties` | — | `local-check` | [[srs-requirements-notes]] | Pending team/GitHub setup | Documented |
| NFR-5 | Input validation; parameterised SQL; no internal errors exposed | user-wallet-design §10; architecture §7 | `GlobalExceptionHandler`, `WalletService`, JDBC `PreparedStatement` repos | `ApiMilestoneIntegrationTest.walletCreationAndLookupByUsernameAndUuidWithOwnershipAndValidation` | — | [[srs-requirements-notes]] | Pending team/GitHub setup | Tested locally |
| NFR-6 | Store only salted hash of API key | user-wallet-design §4.4; ADR-002 | `ApiKeyService`, `UserRepository`, `ApiKeyAuthFilter` | `ApiMilestoneIntegrationTest.userRegistrationValidationDuplicateAndAuthenticationEnforced` | — | [[srs-requirements-notes]] | Pending team/GitHub setup | Tested locally |
| NFR-7 | Restrict access to wallet/ledger/transfer to owner (403) | user-wallet-design §9; architecture §4 | `WalletService`, `TransferService`, `SecurityConfig`, `ApiKeyAuthFilter` | `ApiMilestoneIntegrationTest.walletCreationAndLookupByUsernameAndUuidWithOwnershipAndValidation` | — | [[srs-requirements-notes]] | Pending team/GitHub setup | Tested locally |

---

## Business Rules

| Req ID | Requirement summary | Design / ADR | Implementation | Tests | Skill | Supporting Brain Note | GitHub Issue / PR | Status |
|---|---|---|---|---|---|---|---|---|
| BR-1 | One wallet per user per currency | user-wallet-design §6.2; database-ledger-design §4.2 | `WalletService`, `WalletRepository`, `schema.sql` UNIQUE constraint | `ApiMilestoneIntegrationTest.walletCreationAndLookupByUsernameAndUuidWithOwnershipAndValidation` | `simulate-transfer` | [[monetary-precision]] | Pending team/GitHub setup | Tested locally |
| BR-2 | Transfer amount must be positive | transfer-and-concurrency-design §3.3 | `TransferService` | `TransferAndConcurrencyIntegrationTest.validationAuthAndOwnershipFailuresOnTransfer` | — | [[monetary-precision]] | Pending team/GitHub setup | Tested locally |
| BR-3 | Source wallet balance must not go negative | transfer-and-concurrency-design §3.4 step 5 | `TransferService`, `schema.sql` CHECK constraint | `TransferAndConcurrencyIntegrationTest.insufficientFundsReturns409AndIsNotCachedAsIdempotentSuccess`, `concurrentTransfersPreventOverdrafts` | `simulate-transfer` | [[race-conditions]] | Pending team/GitHub setup | Tested locally |
| BR-4 | Stored balance must agree with ledger-derived balance | user-wallet-design §7.4; database-ledger-design §4.2 | `FundingService`, `TransferService`, `WalletRepository`, `LedgerRepository` | `ApiMilestoneIntegrationTest.adminFundingUpdatesBalanceDoubleEntryLedgerHashChainAndCheckpoint` | `simulate-transfer` | [[ledger-reconciliation]] | Pending team/GitHub setup | Tested locally |
| BR-5 | Reversal cannot be reversed | transfer-and-concurrency-design §6.2 | `TransferService.reverseTransfer` | `ReversalAndLedgerHistoryIntegrationTest.successfulReversalCreatesCompensatingEntriesRestoresBalancesAndPreservesOriginalEntries` | — | [[immutable-audit-trails-and-reversals]] | Pending team/GitHub setup | Tested locally |

---

## Skill Requirements

| Req ID | Skill | File | Status |
|---|---|---|---|
| SK-1 | simulate-transfer | `brain/skills/simulate-transfer/SKILL.md` | Updated & aligned with 14-step PDF workflow |
| SK-2 | local-check | `brain/skills/local-check/SKILL.md` | Updated; aligned with Stages 1–6 |
| SK-3 | brain-ingest | `brain/skills/brain-ingest/SKILL.md` | Exists; valid frontmatter |
| SK-4 | brain-refine | `brain/skills/brain-refine/SKILL.md` | Exists; frontmatter fixed |
| SK-5 | brain-query | `brain/skills/brain-query/SKILL.md` | Exists; frontmatter fixed |

---

## Software Engineering Deliverables

| Req ID | Deliverable | File | Status |
|---|---|---|---|
| SE-1 | SRS v1.3 (authoritative) | `brain/srs/Financial_Ledger_Digital_Wallet_SRS_v1.3.pdf` | Complete (`v1.2` retained as historical) |
| SE-2 | Architecture overview | `docs/architecture.md` | Accepted & aligned |
| SE-2 | Database and ledger design | `docs/design/database-ledger-design.md` | Accepted & aligned |
| SE-2 | User and wallet design | `docs/design/user-wallet-design.md` | Superseded in part by ADRs & username enhancement |
| SE-2 | Transfer and concurrency design | `docs/design/transfer-and-concurrency-design.md` | Accepted & aligned |
| SE-3 | ADR-001 through ADR-006 | `docs/adr/ADR-001`–`ADR-006` | All Accepted |
| SE-4 | Development plan | `docs/development-plan.md` | Documented (Stages 1–6 complete; Stage 7 local complete) |
| SE-5 | Traceability matrix | `docs/traceability-matrix.md` | Complete locally; GitHub fields pending team setup |
| SE-6 | Isolate external dependencies behind a provider interface | N/A — self-contained local API with no external service integrations (SQLite accessed via JDBC repositories) | Not Applicable |
| SE-7 | Root README.md | `README.md` | Documented & locally verified |
| SE-7 | OpenAPI spec | `docs/openapi.yaml` | Documented & YAML-validated locally (12 endpoints) |
| SE-7 | AI-Assisted SDLC records & logs | `ai-sessions/`, `brain/projects/stage-reviews.md`, `defect-log.md`, `skill-evaluations.md` | Documented locally; team PR/CI review pending |
