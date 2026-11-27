# Transfer, Idempotency and Concurrency Design

- **Project:** Financial Ledger & Digital Wallet API
- **Document:** `docs/design/transfer-and-concurrency-design.md`
- **Source baseline:** SRS v1.3, Sections 4.2, 4.3, 4.4, 4.5, 4.6
- **Related designs:** `database-ledger-design.md`, `user-wallet-design.md`
- **Status:** Accepted — all open decisions resolved 2026-11-25; see ADR-003, ADR-005, ADR-006
- **Scope:** Transfer processing, idempotency, concurrent balance protection,
  reversals, error handling, and SQLite locking strategy.
  Database schema is defined in `database-ledger-design.md`.
  User authentication and ownership are defined in `user-wallet-design.md`.

---

## 1. Purpose

This document describes how the system processes transfers between wallets,
prevents double-charges on retried requests, protects balances from race
conditions, and handles reversals.

The design must satisfy:

- **FR-2.1–FR-2.9:** Transfer rules, idempotency, and response contracts.
- **FR-3.1–FR-3.5:** Concurrency safety and locking.
- **FR-4.5–FR-4.6:** Reversals as new compensating transactions.

---

## 2. Requirements summary

| Requirement | Responsibility in this document |
|---|---|
| FR-2.1 | Positive, same-currency transfers; source ≠ destination |
| FR-2.2 | Source wallet must have sufficient funds |
| FR-2.3 | Debit source and credit destination atomically |
| FR-2.4 | Transfer, entries, idempotency, balances and checkpoint commit together |
| FR-2.5 | Idempotency key is scoped per user |
| FR-2.6 | Same key + same data: replay original result |
| FR-2.7 | Same key + different data: 409 Conflict |
| FR-2.8 | Failed/rolled-back attempts are not stored as successes |
| FR-2.9 | Only the source wallet owner may initiate a transfer |
| FR-3.1 | Concurrent transfers do not cause overspending |
| FR-3.2 | Write lock acquired before balance check |
| FR-3.3 | No lost updates from concurrent writes |
| FR-3.4 | No partial writes visible to other transactions |
| FR-3.5 | Tests verify concurrent safety |
| FR-4.5 | Reversal is a new compensating transaction |
| FR-4.6 | At most one reversal per original transfer |

---

## 3. Transfer endpoint

### 3.1 Endpoint

`POST /transfers`

Requires: `X-User-API-Key`, `Idempotency-Key` header.

### 3.2 Request body

```json
{
  "sourceWalletId": "<UUID>",
  "destinationWalletId": "<UUID>",
  "currencyCode": "<code>",
  "amountMinor": 50000
}
```

> **Resolved (ADR-006):** JSON field names and error envelope are accepted. The
> examples in this document match the agreed contracts. See ADR-006 for the
> complete schema.

### 3.3 Validation before the write lock

Perform the following checks before acquiring the write lock. These reads do
not require `BEGIN IMMEDIATE`:

1. Authenticate the caller from `X-User-API-Key`.
2. Validate all UUIDs (FR-1.5).
3. Validate that `sourceWalletId` and `destinationWalletId` are different (FR-2.1).
4. Validate that the currency code is in the supported currency list (`INR` or `USD`, per ADR-005).
5. Validate that `amountMinor` is a positive integer (FR-2.1).
6. Verify the caller owns the source wallet (FR-2.9, NFR-7).
7. Verify the destination wallet exists and uses the same currency (FR-2.1).

If any check fails, return the appropriate 4xx response without acquiring the
write lock.

### 3.4 Idempotency check and write transaction

After the pre-lock validation, acquire the write lock and proceed:

```sql
BEGIN IMMEDIATE;
```

Inside the transaction:

1. Look up the `(user_id, idempotency_key)` pair in `idempotency_records`.

2. **Case: record exists with matching fingerprint (retry).**
   Retrieve the original `transfer_id`. Reconstruct or replay the original
   response. `COMMIT` (read-only result). Return `200 OK` with the original
   response.

3. **Case: record exists with different fingerprint (conflict).**
   `COMMIT` (no write needed). Return `409 Conflict`.

4. **Case: no existing record (new request).**
   Continue with the steps below.

5. Check source wallet balance again inside the write transaction (this is the
   authoritative check under FR-3.2).

6. If balance < `amountMinor`, `ROLLBACK`. Return `409 Conflict`
   (`INSUFFICIENT_FUNDS`, per SRS v1.3 / ADR-006).

7. Read the current last `sequence_no` and `chain_head_hash` from
   `integrity_checkpoints`.

8. Insert the `Transfer` record with `status = 'COMPLETED'`.

9. Insert the `LedgerTransaction` with `type = 'TRANSFER'` and
   `reference_id = transfer_id`.

10. Insert two `LedgerEntry` rows:
    - Debit entry for the source wallet's ledger account.
    - Credit entry for the destination wallet's ledger account.
    - Each entry includes `sequence_no`, `previous_hash`, and `entry_hash`.

11. Update `stored_balance_minor` for both wallets atomically.

12. Insert the `IdempotencyRecord` with the fingerprint and `transfer_id`.

13. Update the `IntegrityCheckpoint` with the new chain head and last sequence.

14. `COMMIT`.

If any step fails, `ROLLBACK`. The failed attempt is not cached as a success
(FR-2.8). Return the appropriate error.

### 3.5 Request fingerprint

The fingerprint is a deterministic hash of:
- `user_id`
- `sourceWalletId`
- `destinationWalletId`
- `amountMinor`
- `currencyCode`

This allows the server to detect when a client retries with the same key but
changed parameters (FR-2.7).

> **Resolved (ADR-006):** SHA-256 over the canonical concatenation of the fields above.

### 3.6 Transfer response

**201 Created** (new transfer):
```json
{
  "transferId": "<UUID>",
  "sourceWalletId": "<UUID>",
  "destinationWalletId": "<UUID>",
  "currencyCode": "INR",
  "amountMinor": 50000,
  "status": "COMPLETED",
  "createdAt": "2026-11-25T14:30:00Z"
}
```

**200 OK** (idempotent replay): same body as the original `201` response.

> **Resolved (ADR-006):** Field names and response format are finalized.

### 3.7 Transfer errors

| Situation | HTTP status |
|---|---|
| Transfer succeeds (new) | 201 |
| Idempotent replay | 200 |
| Missing API key | 401 |
| Invalid API key | 401 |
| Caller does not own source wallet | 403 |
| Malformed UUID | 400 |
| Source == destination | 400 |
| Unsupported currency | 400 |
| Amount ≤ 0 | 400 |
| Currency mismatch | 400 |
| Source wallet not found | 404 |
| Destination wallet not found | 404 |
| Insufficient funds | 409 |
| Idempotency key conflict (different data) | 409 |
| Database contention (after retries exhausted) | 503 |

---

## 4. Retrieve transfer

### 4.1 Endpoint

`GET /transfers/{transferId}`

Requires: `X-User-API-Key`.

### 4.2 Access control

The caller must own either the source wallet or the destination wallet of the
requested transfer (FR-2.9). If the caller owns neither, return `403 Forbidden`.

### 4.3 Response

Returns transfer details as in Section 3.6. If the transfer has been reversed,
the `status` field is `'REVERSED'`.

---

## 5. Concurrency and SQLite locking

### 5.1 SQLite write serialisation

SQLite permits only one write transaction at a time. The write lock is enforced
at the database level.

**Rule (FR-3.2):** The write lock must be acquired before any balance check
that is used to decide whether to proceed with a financial operation.

The proposed write-lock statement:

```sql
BEGIN IMMEDIATE;
```

This acquires the write lock at transaction start. If another writer holds the
lock, `BEGIN IMMEDIATE` returns `SQLITE_BUSY` immediately (not deferred until
the first write statement). See [[sqlite-wal-mode]] and [[race-conditions]].

> **Resolved (ADR-003):** Use explicit JDBC with `autoCommit = true` throughout.
> Do **not** call `connection.setAutoCommit(false)` before `BEGIN IMMEDIATE`,
> because the Xerial `sqlite-jdbc` driver issues `BEGIN` (deferred) on
> `setAutoCommit(false)` and a subsequent `BEGIN IMMEDIATE` fails with
> `cannot start a transaction within a transaction`. Keep `autoCommit = true`,
> execute `BEGIN IMMEDIATE`, run all `PreparedStatement` operations, and
> explicitly execute `COMMIT` or `ROLLBACK`. See ADR-003 and [[spring-transactional]].

### 5.2 SQLITE_BUSY handling

When `BEGIN IMMEDIATE` returns `SQLITE_BUSY`:

1. Wait a brief configurable period.
2. Retry up to a configurable maximum number of times.
3. If retries are exhausted, return `503 Service Unavailable` with a
   `Retry-After` header.

> **Resolved (ADR-003):** Retry up to **3 times** with a **50 ms** wait between
> attempts. If retries are exhausted, return `503 Service Unavailable` with
> `Retry-After: 1`.

Retries of a request must reuse the original `Idempotency-Key` header so that
a successful retry does not create a duplicate transfer (FR-2.5).

### 5.3 Why pre-lock validation is not sufficient

A balance check performed before `BEGIN IMMEDIATE` can be stale. Another
concurrent request may have committed a debit between the read and the lock
acquisition. By re-reading the balance inside the write transaction, the system
guarantees that it acts on the current state (FR-3.2, FR-3.3). See
[[race-conditions]] for the lost-update scenario.

### 5.4 SQLite WAL mode

Enable WAL mode once at database creation:

```sql
PRAGMA journal_mode = WAL;
```

WAL allows concurrent readers while a single writer holds the lock. Balance
read requests (e.g., `GET /wallets/{walletId}`) are not blocked by an active
write transaction. See [[sqlite-wal-mode]].

### 5.5 Additional SQLite connection settings

Enable foreign keys on every connection:

```sql
PRAGMA foreign_keys = ON;
PRAGMA synchronous  = FULL;
PRAGMA busy_timeout = 5000;
```

> **Resolved (ADR-003):** `busy_timeout = 5000` (5 seconds internal wait) and
> `synchronous = FULL` (durable disk sync before commit returns) are set on
> every connection. See [[sqlite-wal-mode]].

---

## 6. Reversal

### 6.1 Endpoint

`POST /transfers/{transferId}/reversal`

Requires: `X-User-API-Key`.

### 6.2 Eligibility checks before the write lock

Before acquiring the write lock:

1. Authenticate the caller.
2. Retrieve the original `Transfer`.
3. Check the caller owns the original source wallet (FR-4.5).
4. Check `status == 'COMPLETED'` (not already `'REVERSED'`).
5. Check `reversal_of_transfer_id IS NULL` (original is not itself a reversal;
   FR-4.6).

### 6.3 Reversal write transaction

```sql
BEGIN IMMEDIATE;
```

Inside the transaction:

1. Re-read the original transfer for consistency.
2. Check the original transfer has not already been reversed (re-read under
   the write lock to prevent concurrent reversal attempts; FR-4.6).
3. Check the destination wallet (now acting as source for the reversal) has
   sufficient funds.
4. Read current `sequence_no` and `chain_head_hash`.
5. Insert a reversal `Transfer` record:
   - `source_wallet_id = original.destination_wallet_id`
   - `destination_wallet_id = original.source_wallet_id`
   - `amount_minor = original.amount_minor`
   - `currency_code = original.currency_code`
   - `status = 'COMPLETED'`
   - `reversal_of_transfer_id = original.transfer_id`
6. Insert a `LedgerTransaction` with `type = 'REVERSAL'`.
7. Insert two new `LedgerEntry` rows (swapped debit/credit).
8. Update both wallet stored balances.
9. Update the original `Transfer.status` to `'REVERSED'`.
10. Update `IntegrityCheckpoint`.
11. `COMMIT`.

The original ledger entries are never modified (FR-4.1). See
[[immutable-audit-trails-and-reversals]].

### 6.4 Reversal response

**201 Created:**
```json
{
  "transferId": "<UUID>",
  "sourceWalletId": "<UUID>",
  "destinationWalletId": "<UUID>",
  "currencyCode": "INR",
  "amountMinor": 50000,
  "status": "COMPLETED",
  "reversalOf": "<UUID>",
  "createdAt": "2026-11-25T15:00:00Z"
}
```

> **Resolved (ADR-006):** Field names and response format are finalized.

### 6.5 Reversal errors

| Situation | HTTP status |
|---|---|
| Reversal created | 201 |
| Missing API key | 401 |
| Caller does not own original source wallet | 403 |
| Transfer not found | 404 |
| Transfer already reversed | 409 |
| Original is itself a reversal | 409 |
| Destination has insufficient funds for reversal | 409 |
| Database contention | 503 |

---

## 7. Error response format

> **Resolved (ADR-006):** Standard error envelope adopted across all endpoints:

```json
{
  "error": {
    "code": "INSUFFICIENT_FUNDS",
    "message": "The source wallet does not have sufficient funds."
  }
}
```

Error responses must not expose:
- Stack traces.
- SQL queries or database paths.
- API keys, salts, or hashes.
- Other users' private information.

---

## 8. Resolved decisions

| ID | Decision | Agreed approach | Status |
|---|---|---|---|
| TBD-01 | Supported currencies and minor-unit scales | `INR` and `USD` only, both scale 2; auto-init system funding accounts | **Accepted (ADR-005)** |
| TBD-02 | JSON request/response/error/pagination contracts | Agreed schemas, error envelope, ascending cursor pagination | **Accepted (ADR-006)** |
| TBD-03 | Spring `@Transactional` vs explicit JDBC `BEGIN IMMEDIATE` | Explicit JDBC with `autoCommit=true` + `BEGIN IMMEDIATE` | **Accepted (ADR-003)** |
| TBD-06 | SQLite busy timeout, synchronous mode, retry count | `busy_timeout=5000`, `synchronous=FULL`, 3 retries × 50 ms, `503 Retry-After: 1` | **Accepted (ADR-003)** |

---

## 9. References

- SRS v1.3: `brain/srs/Financial_Ledger_Digital_Wallet_SRS_v1.3.pdf`
  Sections 4.2 (Idempotent Transfers), 4.3 (Concurrent Balance Mutability),
  4.4 (Immutable Audit Trail and Reversals)
- [[sqlite-acid-transactions-final]]
- [[sqlite-wal-mode]]
- [[race-conditions]]
- [[api-idempotency-keys]]
- [[spring-transactional]]
- [[immutable-audit-trails-and-reversals]]
- [[double-entry-accounting]]
- ADR-003: SQLite WAL and BEGIN IMMEDIATE
- ADR-004: Ledger hash chain and HMAC checkpoint
- ADR-005: Supported currencies
- ADR-006: JSON API contracts
