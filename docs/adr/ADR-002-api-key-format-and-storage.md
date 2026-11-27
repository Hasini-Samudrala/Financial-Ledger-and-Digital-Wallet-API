# ADR-002: API key format and User table schema

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** Team 404 (all members approved 2026-11-25)
- **SRS reference:** FR-1.1, NFR-6, Appendix B (User entity)

---

## Context

Two conflicting designs existed in the repository before this ADR:

| Document | User table columns | API key format |
|---|---|---|
| `user-wallet-design.md` (and SRS Appendix B) | `user_id`, `api_key_hash`, `api_key_salt` | `flw_<userId>.<secret>` |
| `database-ledger-design.md` (earlier draft) | `user_id`, `api_key_id`, `api_key_hash`, `api_key_salt` | `<api_key_id>.<secret>` |

The SRS Appendix B lists exactly three columns for the User entity.

The core problem this design must solve: a salted hash cannot be used as a
direct database lookup because the salt is unique per user. A lookup strategy
is needed.

## Decision

**Align to the three-column `users` table from SRS Appendix B and
`user-wallet-design.md`.** The `api_key_id` column in the earlier draft is
removed; `user_id` acts as the public identifier embedded in the API key.

### API key format

```
flw_<userId>.<secret>
```

- `flw_` — fixed prefix, common to all keys issued by this system.
- `<userId>` — the UUID primary key of the user record (e.g. `550e8400-e29b-41d4-a716-446655440000`).
- `.` — literal separator.
- `<secret>` — 256-bit (32 bytes) cryptographically random token encoded as
  a 64-character lowercase hexadecimal string.

**Example key (illustrative — not a real secret):**

```
flw_550e8400-e29b-41d4-a716-446655440000.a3f1c2d4e5b6a7f8c9d0e1f2a3b4c5d6e7f8a9b0c1d2e3f4a5b6c7d8e9f0a1b2
```

### Secret generation

```
byte[] secretBytes = new byte[32];
SecureRandom.getInstanceStrong().nextBytes(secretBytes);
String secret = HexFormat.of().formatHex(secretBytes); // 64 hex chars
```

The 64-character hex-encoded secret is appended after the `.` separator.

### User table schema (aligned to SRS Appendix B + username enhancement)

```sql
CREATE TABLE users (
    user_id      TEXT    PRIMARY KEY,          -- UUID, e.g. '550e8400-...'
    username     TEXT    NOT NULL,             -- 3-20 chars [A-Za-z0-9_], case-insensitive unique
    api_key_hash BLOB    NOT NULL,             -- SHA-256(salt || secret), 32 bytes
    api_key_salt BLOB    NOT NULL              -- 32-byte random salt
);

CREATE UNIQUE INDEX uq_users_username_lower ON users(LOWER(username));
```

No `api_key_id` column. The `user_id` embedded in the key prefix remains the
internal primary key and lookup handle for authentication.

### Verification algorithm

1. Split the supplied key on the first `.` after the `flw_` prefix to extract
   `suppliedUserId` and `suppliedSecret`.
2. Query `users` by `user_id = suppliedUserId`.
3. If no row is found, return 401 without revealing whether the user exists.
4. Decode `suppliedSecret` from hex to 32 bytes.
5. Compute `candidate = SHA-256(storedSalt || suppliedSecretBytes)`.
6. Compare `candidate` with `storedApiKeyHash` using
   `MessageDigest.isEqual(candidate, storedApiKeyHash)` (constant-time).
7. If equal, authentication succeeds; otherwise return 401.

> **Security note:** `MessageDigest.isEqual` runs in time proportional to the
> length of the arrays, not their content, so it does not leak the hash value
> through timing. Do not use `Arrays.equals` or `==`.

### Error responses

- Any missing or malformed API key → `401 Unauthorized`.
- Key with valid structure but unknown `userId` → `401 Unauthorized`
  (do **not** reveal whether the userId exists).
- Valid key for a different user's resource → `403 Forbidden`.

### Key issuance

The registration endpoint (`POST /users` with `{"username":"..."}`) returns the full API key **once**:

```json
{
  "userId": "550e8400-e29b-41d4-a716-446655440000",
  "username": "alice_01",
  "apiKey": "flw_550e8400-e29b-41d4-a716-446655440000.<hex-secret>"
}
```

The raw secret is never stored. Only the salted hash and salt are persisted.

## Consequences

| Positive | Negative |
|---|---|
| Three-column schema matches SRS Appendix B exactly | userId embedded in key — user must not reuse keys if userId changes (rotation reissues a new key) |
| Lookup by userId is a primary-key index scan (O(1)) | Key is longer than a random token alone |
| No timing side-channel with `MessageDigest.isEqual` | — |
| Salt prevents precomputed rainbow-table attacks | — |

## Alternatives considered

**`<api_key_id>.<secret>` format:** Requires a fourth `api_key_id` column
(conflicts with SRS Appendix B) and a separate index. Rejected.

**Argon2 / bcrypt instead of SHA-256:** Appropriate for password hashing but
API keys are already 256-bit random secrets — the hash is for storage
integrity, not password strengthening. SHA-256 with a random salt is
sufficient and faster. Rejected for this project.

## Sign-off

All team members approved 2026-11-25. Status changed from APPROVAL-REQUIRED to Accepted.
