---
name: simulate-transfer
description: Runs a full end-to-end verification of user registration, wallet creation, admin funding, idempotent transfer, concurrent replay, concurrent overdraft protection, ledger entries, and reconciliation on an isolated test instance.
---

# Skill: simulate-transfer

## Purpose

Verify the core transfer, idempotency, concurrency, and reconciliation flows
end-to-end on an isolated, locally running instance of the Financial Ledger API.

This skill must only be run against a dedicated test environment/database. It
must never be run against a database holding real or production data.

---

## Prerequisites

- The application is running locally against a **dedicated test database**
  (for example `SPRING_DATASOURCE_URL=jdbc:sqlite:./target/smoke-test-data/smoke.db`)
  and `GET /health` returns `200 {"status":"UP"}`.
- `ADMIN_API_KEY` and `LEDGER_HMAC_SECRET` (≥ 32 bytes Base64) are configured
  for the test instance.
- `curl` or an equivalent HTTP client is available.

---

## Instructions for the AI

When asked to execute `simulate-transfer`:

1. **Register two test users** using `POST /users` with unique usernames
   (`{"username":"sim_alice_<rand>"}` and `{"username":"sim_bob_<rand>"}`).
   Record each `userId`, `username`, and one-time `apiKey` in memory only (never
   persist or log API keys).

2. **Create source and destination wallets** using `POST /users/{username}/wallets`
   with `X-User-API-Key` and `{"currencyCode":"INR"}` (see ADR-005).

3. **Record initial balances** using `GET /users/{username}/wallets/{currencyCode}`
   (or `GET /wallets/{walletId}`) and confirm both start at `0`.

4. **Fund the source wallet** using `POST /admin/funding` with `X-Admin-API-Key`
   (e.g., `amountMinor: 10000`, `currencyCode: "INR"`). Confirm `balanceMinor`
   updates to `10000`.

5. **Execute a transfer** using `POST /transfers` with `X-User-API-Key` for User A,
   a unique `Idempotency-Key` (1–64 ASCII chars), and `amountMinor: 4000`.
   Confirm `201 Created` and record `transferId`.

6. **Retry the exact same request** using the same `Idempotency-Key` and body.

7. **Verify no duplicate transfer occurred:** confirm balances remain `6000`
   (source) and `4000` (destination), and ledger entry count did not increase.

8. **Verify the replay response:** confirm `200 OK`, header
   `Idempotent-Replayed: true`, and the same `transferId`.

9. **Test concurrent requests with the same `Idempotency-Key`:** send at least
   two simultaneous `POST /transfers` requests with a new shared `Idempotency-Key`
   (e.g., `amountMinor: 2000`).

10. **Verify only one actual transfer occurred:** one `201 Created`, the other
    `200 OK` (`Idempotent-Replayed: true`), same `transferId`, source balance `4000`,
    destination balance `6000`.

11. **Test concurrent transfers with different `Idempotency-Key`s** whose combined
    amounts exceed the remaining source balance (`4000`) — e.g., two concurrent
    requests of `3000` each with distinct keys.

12. **Verify overdraft does not occur:** exactly one succeeds (`201 Created`),
    the other fails with `409 INSUFFICIENT_FUNDS`, and final source balance is
    `1000` (never negative).

13. **Run reconciliation** via `GET /admin/reconciliation` with `X-Admin-API-Key`
    and confirm `status: "PASS"`, balanced currency totals, empty
    `unbalancedTransactions`, and empty `walletDiscrepancies`.

14. **Summarize the results** including initial balances, transfer outcomes,
    replay/concurrency results, final balances, and reconciliation output.

---

## Safety rules

- Always point the server at a disposable test database; never target `./data/financial_ledger.db`.
- Never log or commit API keys or `LEDGER_HMAC_SECRET`.
- Never modify or delete ledger entries during verification.
- Never report a step as passed unless it was actually executed and observed.

---

## Research references

- [[api-idempotency-keys]]
- [[double-entry-accounting]]
- [[race-conditions]]
- [[ledger-reconciliation]]
- [[integration-testing-and-rollback]]
