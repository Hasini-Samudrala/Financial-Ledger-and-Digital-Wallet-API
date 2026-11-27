# Development Plan — Financial Ledger & Digital Wallet API

- **Document:** `docs/development-plan.md`
- **Source baseline:** SRS v1.3; AI-Assisted SDLC and Workflow PDF
- **Status:** Accepted — prerequisites resolved (ADR-002 through ADR-006 Accepted 2026-11-25)
- **Date:** 2026-11-25

> **Prerequisite status:** All five blocking ADRs (`ADR-002` through `ADR-006`)
> are **Accepted** as of 2026-11-25. Stage 1 implementation is cleared to proceed.

---

## Overview

The implementation is divided into seven stages. Each stage builds on the
previous one and must pass its own tests before the next stage begins.
All changes go through pull requests reviewed by at least one teammate.
All AI-generated changes must be reviewed by a human before merging.

---

## Stage 1 — Foundation: database, security, users and wallets — COMPLETED (2026-11-26)

**SRS coverage:** FR-1.1, FR-1.2, FR-1.3, FR-1.5, NFR-5, NFR-6, NFR-7, BR-1

**Goals:**
- Create the `data/` directory and SQLite database file at startup.
- Run the full DDL (all 8 tables, triggers, indexes from `database-ledger-design.md`).
- Enable WAL mode and foreign keys on every connection.
- Configure the Spring `SecurityFilterChain` for `X-User-API-Key` and
  `X-Admin-API-Key` authentication; permit public `GET /health` and `POST /users`.
- Implement `GET /health` returning `{"status":"UP"}`.
- Implement `POST /users`: validate unique username (3–20 `[A-Za-z0-9_]`, case-insensitive), generate UUID, generate random secret, hash with salt,
  store hash + salt, return userId, username, and API key once.
- Implement `POST /users/{username}/wallets`: resolve username to userId, authenticate, ownership check,
  currency validation, duplicate check, atomic insert of wallet + ledger account.
- Implement `GET /users/{username}/wallets/{currencyCode}` and `GET /wallets/{walletId}`: authenticate, ownership check, return
  wallet details and stored balance.

**Definition of done:**
- Build passes (`.\mvnw.cmd test`).
- Unit tests for API key generation, hashing, and wallet creation rules.
- Integration tests: register user, create wallet, verify DB records, verify
  initial balance = 0.
- `GET /health` returns 200 without credentials.
- `POST /users` returns 201 with userId and apiKey; apiKey not stored in DB.
- Duplicate wallet creation returns 409.
- Wrong-user wallet access returns 403.
- `local-check` skill runs clean.

---

## Stage 2 — Funding and double-entry ledger — COMPLETED (2026-11-26)

**SRS coverage:** FR-1.4, FR-4.1, FR-4.2, BR-4

**Goals:**
- Implement `POST /admin/funding` with `X-Admin-API-Key` authentication.
- Create a `SYSTEM_FUNDING` ledger account for each supported currency at startup.
- Record double-entry transactions: debit system account, credit wallet account.
- Update `stored_balance_minor` atomically within the same `BEGIN IMMEDIATE`
  transaction.
- Add the immutability triggers to the database schema (`prevent_ledger_entry_update`,
  `prevent_ledger_entry_delete`, `prevent_ledger_transaction_update`,
  `prevent_ledger_transaction_delete`).

**Definition of done:**
- Funding creates a `LedgerTransaction` and two balanced `LedgerEntry` rows.
- Wallet stored balance increases by the funded amount.
- Immutability triggers reject `UPDATE` and `DELETE` on `ledger_entries`.
- Integration test: fund wallet, verify stored balance, verify ledger entries.

---

## Stage 3 — Transfers and idempotency — COMPLETED (2026-11-26)

**SRS coverage:** FR-2.1–FR-2.9, BR-2

**Goals:**
- Implement `POST /transfers`: full validation, `BEGIN IMMEDIATE` write lock,
  idempotency check, balanced double-entry write, atomic commit.
- Implement `GET /transfers/{transferId}`: return transfer details to source or
  destination wallet owner.
- Handle idempotency replay (same key + same fingerprint → 200 replay).
- Handle idempotency conflict (same key + different fingerprint → 409).
- Handle insufficient funds → 409.

**Definition of done:**
- New transfer → 201 with correct response.
- Idempotent retry → 200 with original response; no duplicate entries.
- Conflict key → 409.
- Insufficient funds → 409; no DB changes.
- Both wallet balances updated correctly.
- Two ledger entries per transfer; debit == credit.

---

## Stage 4 — Concurrency and SQLite locking — COMPLETED (2026-11-26)

**SRS coverage:** FR-3.1–FR-3.5

**Goals:**
- Confirm `BEGIN IMMEDIATE` is used for all financial writes.
- Implement application-level `SQLITE_BUSY` retry with configurable count and
  wait interval.
- Return `503 Service Unavailable` with `Retry-After` when retries are exhausted.
- Write a multi-connection concurrency integration test (two threads competing
  for the same wallet balance; verify no overspending and no lost updates).

**Definition of done:**
- Concurrency test: concurrent transfer requests for same source wallet; balance
  never goes negative; at most one succeeds for each logical unit of funds.
- `503` response returned on sustained contention.
- Balance reads outside the write transaction are never used as the authoritative
  balance for financial decisions.

---

## Stage 5 — Integrity, reversals and ledger history — COMPLETED (2026-11-26)

**SRS coverage:** FR-4.1–FR-4.7

**Goals:**
- Implement SHA-256 hash chain: each `LedgerEntry` stores `previous_hash` and
  `entry_hash` computed at insert time within the write transaction.
- Update `IntegrityCheckpoint` atomically with every financial write.
- Implement `GET /admin/ledger-integrity`: recompute chain, verify MAC, report
  earliest mismatch.
- Implement `POST /transfers/{transferId}/reversal`: eligibility checks, `BEGIN
  IMMEDIATE` write, new compensating entries, status update.
- Implement `GET /wallets/{walletId}/ledger-entries`: authenticate, ownership,
  keyset pagination (max 100 entries, ordered by `sequence_no`).

**Definition of done:**
- Hash chain is continuous; integrity check passes on a clean ledger.
- Altering an entry causes the integrity check to report the first mismatch.
- Reversal creates new entries; original entries unchanged; status = `REVERSED`.
- A second reversal of the same transfer returns 409.
- Ledger history returns entries in chronological order with correct pagination.

---

## Stage 6 — Reconciliation — COMPLETED (2026-11-26)

**SRS coverage:** FR-5.1–FR-5.4

**Goals:**
- Implement `GET /admin/reconciliation` as a read-only operation inside a single
  `BEGIN DEFERRED` read transaction.
- Report: total debits vs credits per currency; unbalanced transactions;
  stored balance vs ledger-derived balance per wallet.
- Return a structured JSON report; never modify any data.

**Definition of done:**
- Balanced ledger → reconciliation reports PASS.
- Artificially introduced balance discrepancy → reported with wallet ID and
  difference amount.
- Reconciliation does not change any database row.

---

## Stage 7 — Tests, Skills, Second Brain, CI, documentation and final verification — LOCAL DELIVERABLES COMPLETE (2026-11-26; GitHub/CI deferred)

**SRS coverage:** TQ-1–TQ-8, SK-1–SK-5, SE-1–SE-7, CI-1–CI-6

**Status note:**
- **Implemented & tested locally:** All 29 JUnit tests, `README.md`, `docs/openapi.yaml` (12 endpoints), `ai-sessions/`, Agent Skills alignment, and Second Brain/SDLC documentation.
- **Not yet externally verified (deferred by team):** GitHub repository push, branch/PR workflow, and `.github/workflows/ci.yml` execution.

**Goals:**
- Ensure full unit test coverage for all business-logic classes (TQ-1).
- Add rollback tests for simulated mid-transfer failures (TQ-2).
- Run the `simulate-transfer` skill end-to-end (SK-1).
- Run the `local-check` skill against the full running application (SK-2).
- Expand `.github/workflows/ci.yml` to: build, test, secret scan, `SKILL.md`
  frontmatter validation, Second Brain wikilink validation.
- Write OpenAPI documentation (or equivalent).
- Finalise and sign-off all ADRs.
- Update the traceability matrix (`docs/traceability-matrix.md`) to link every
  FR/NFR/BR to its design section, test, and implementation file.
- Record a final stage review note in `brain/log.md`.

**Definition of done:**
- All tests pass in CI.
- `simulate-transfer` skill produces a full PASS report.
- All ADRs have team sign-off.
- All open APPROVAL-REQUIRED items are resolved.
- Traceability matrix is complete.
- OpenAPI spec describes all 11 endpoints.
- `local-check` skill passes with the running application.
