# ADR-006: JSON API contracts, error envelope and pagination

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** Team 404 (all members approved 2026-11-25)
- **SRS reference:** SRS Appendix C TBD-02, SRS §3.4 (error handling),
  FR-4.7 (ledger history pagination)
- **Research notes:** [[api-idempotency-keys]], [[monetary-precision]]

---

## Context

The design documents contained illustrative JSON examples but no agreed schema.
Inconsistent field names and error formats across endpoints would make client
integration difficult and cause test failures. This was a blocking decision
before implementation.

## Decision

### Error envelope

All error responses use this envelope — no exceptions:

```json
{
  "error": {
    "code": "MACHINE_READABLE_CODE",
    "message": "Human-readable description."
  }
}
```

- `code` — uppercase snake-case machine-readable identifier (see catalogue below).
- `message` — brief English description safe for end users.
- **Never** include: stack traces, SQL text, internal IDs, API key fragments,
  hash values, or salts.

#### Error code catalogue

| HTTP status | `code` | Trigger |
|---|---|---|
| 400 | `VALIDATION_ERROR` | Missing/invalid request fields |
| 400 | `UNSUPPORTED_CURRENCY` | Currency code not in `{INR, USD}` |
| 400 | `CURRENCY_MISMATCH` | Source and destination wallet currencies differ |
| 400 | `INVALID_AMOUNT` | `amountMinor` ≤ 0 or non-integer |
| 401 | `INVALID_API_KEY` | Missing, malformed, or unrecognised API key |
| 403 | `FORBIDDEN` | Authenticated user does not own the resource |
| 404 | `WALLET_NOT_FOUND` | Wallet UUID or currency wallet not found |
| 404 | `USER_NOT_FOUND` | Username does not exist |
| 404 | `TRANSFER_NOT_FOUND` | Transfer UUID not found |
| 409 | `DUPLICATE_USERNAME` | Username already taken (case-insensitive) |
| 409 | `DUPLICATE_WALLET` | User already has a wallet in that currency |
| 409 | `IDEMPOTENCY_CONFLICT` | Same `Idempotency-Key` with different request data |
| 409 | `TRANSFER_NOT_REVERSIBLE` | Transfer already reversed or not eligible |
| 409 | `INSUFFICIENT_FUNDS` | Source wallet balance < `amountMinor` |
| 503 | `SERVICE_UNAVAILABLE` | SQLite write lock exhausted after retries |

---

### Endpoints and shapes

#### POST /users — Register

No authentication required.

**Request body:**
```json
{
  "username": "alice_01"
}
```

**Response 201:**
```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "alice_01",
  "apiKey": "flw_550e8400-e29b-41d4-a716-446655440000.<hex-secret>"
}
```

> The `apiKey` is returned **once only** at registration. It is never stored
> in plain form and cannot be retrieved again. The user must save it immediately.

---

#### POST /users/{username}/wallets — Create wallet

**Request header:** `X-User-API-Key: flw_<userId>.<secret>` (authenticated user must own `{username}`)

**Request body:**
```json
{
  "currencyCode": "INR"
}
```

**Response 201:**
```json
{
  "walletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "currencyCode": "INR",
  "balanceMinor": 0
}
```

---

#### GET /users/{username}/wallets/{currencyCode} — Get wallet by username and currency

**Request header:** `X-User-API-Key: flw_<userId>.<secret>` (authenticated user must own `{username}`)

**Response 200:**
```json
{
  "walletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "currencyCode": "INR",
  "balanceMinor": 10000
}
```

---

#### GET /wallets/{walletId} — Get wallet

**Request header:** `X-User-API-Key: flw_<userId>.<secret>`

**Response 200:**
```json
{
  "walletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "currencyCode": "INR",
  "balanceMinor": 10000
}
```

---

#### POST /admin/funding — Admin fund wallet

**Request header:** `X-Admin-API-Key: <admin-key>`

**Request body:**
```json
{
  "walletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "amountMinor": 10000,
  "currencyCode": "INR"
}
```

**Response 200:**
```json
{
  "walletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "balanceMinor": 10000,
  "currencyCode": "INR"
}
```

---

#### POST /transfers — Initiate transfer

**Request headers:**
```
X-User-API-Key: flw_<userId>.<secret>
Idempotency-Key: <1–64 ASCII printable characters>
```

**Request body:**
```json
{
  "sourceWalletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "destinationWalletId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "currencyCode": "INR",
  "amountMinor": 50000
}
```

**Response 201 (new transfer) / 200 (idempotent replay):**
```json
{
  "transferId": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
  "sourceWalletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "destinationWalletId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "currencyCode": "INR",
  "amountMinor": 50000,
  "status": "COMPLETED",
  "createdAt": "2026-11-25T14:30:00Z"
}
```

**Idempotency-Key rules:**
- Scoped per authenticated user (FR-2.5).
- 1–64 ASCII printable characters (codes 0x20–0x7E).
- Same key + same body → 200 with original result (FR-2.6).
- Same key + different body → 409 `IDEMPOTENCY_CONFLICT` (FR-2.7).
- Failed (rolled-back) attempts are not cached (FR-2.8).

---

#### POST /transfers/{transferId}/reversal — Reverse transfer

**Request header:** `X-User-API-Key: flw_<userId>.<secret>`

No request body.

**Response 201:**
```json
{
  "transferId": "b1ffcd00-0d1c-4fa9-cc7e-7cc0ce491b22",
  "sourceWalletId": "7c9e6679-7425-40de-944b-e07fc1f90ae7",
  "destinationWalletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "currencyCode": "INR",
  "amountMinor": 50000,
  "status": "COMPLETED",
  "reversalOf": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
  "createdAt": "2026-11-25T14:35:00Z"
}
```

---

#### GET /wallets/{walletId}/ledger-entries — Ledger history

**Request header:** `X-User-API-Key: flw_<userId>.<secret>`

**Query parameters:**

| Parameter | Type | Default | Maximum | Description |
|---|---|---|---|---|
| `cursor` | integer | 0 | — | Exclusive sequence cursor; return entries where `sequence_no > cursor` |
| `limit` | integer | 20 | 100 | Number of entries per page; clamped to 100 |

**Response 200:**
```json
{
  "walletId": "6ba7b810-9dad-11d1-80b4-00c04fd430c8",
  "entries": [
    {
      "entryId": "d290f1ee-6c54-4b01-90e6-d701748f0851",
      "sequenceNo": 1,
      "transactionId": "a0eebc99-9c0b-4ef8-bb6d-6bb9bd380a11",
      "currencyCode": "INR",
      "debitMinor": 0,
      "creditMinor": 10000,
      "createdAt": "2026-11-25T14:30:00Z"
    }
  ],
  "nextCursor": 1
}
```

**Pagination rules:**
- Entries are ordered **chronologically ascending** by `sequence_no`.
- `cursor` is **exclusive**: the first page starts with `cursor = 0` (or absent),
  which returns entries with `sequence_no > 0`, i.e. all entries.
- `nextCursor` is the `sequence_no` of the **last entry returned**, or `null`
  if there are no more entries (the page was smaller than `limit`).
- The caller passes `?cursor=<nextCursor>` to fetch the next page.

**Fields NOT included in ledger responses:**
- `entry_hash` — internal integrity field; never exposed via API.
- `previous_hash` — internal integrity field; never exposed via API.

---

### Timestamps

All timestamps use **ISO 8601 UTC** format with `Z` suffix:

```
2026-11-25T14:30:00Z
```

Sub-second precision is optional but must be consistent if used.

---

### Content-Type

All request and response bodies use `Content-Type: application/json`.

---

## Consequences

| Positive | Negative |
|---|---|
| Single consistent error envelope across all endpoints | Error code list must be kept in sync with implementation |
| Exclusive sequence cursor is stateless and consistent | Pagination skips entries inserted with lower sequence numbers after the cursor was read (acceptable for append-only ledger) |
| `nextCursor: null` unambiguously signals end of results | Clients must handle both integer and null `nextCursor` |
| Hash fields excluded from API — protects chain integrity metadata | Integrity verification must use admin-only endpoints, not the public ledger API |

## Resolves

- **TBD-02** (SRS Appendix C): JSON field names and error envelope — resolved by this ADR.

## Sign-off

All team members approved 2026-11-25. Status changed from APPROVAL-REQUIRED to Accepted.
