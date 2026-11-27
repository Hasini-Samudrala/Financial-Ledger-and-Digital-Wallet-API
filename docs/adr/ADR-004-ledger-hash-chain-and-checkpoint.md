# ADR-004: Ledger hash chain and HMAC-SHA-256 checkpoint

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** Team 404 (all members approved 2026-11-25)
- **SRS reference:** FR-4.2 (LedgerEntry fields), FR-4.3 (hash-chain integrity
  check), FR-4.4 (external-secret checkpoint)
- **Research notes:** [[immutable-audit-trails-and-reversals]], [[double-entry-accounting]]

---

## Context

The SRS requires:

- **FR-4.2:** Every committed ledger entry stores a sequence number, a previous
  hash, and its own hash.
- **FR-4.3:** The admin integrity operation recomputes the chain and reports the
  earliest mismatch.
- **FR-4.4:** An authenticated external-secret checkpoint detects tampering that
  rewrites the entire chain.

The SRS does not specify the hash algorithm, genesis value, canonical encoding,
HMAC algorithm, or how the external secret is stored.

## Decision

### Hash algorithm

**SHA-256.** Each ledger entry's hash is computed as:

```
entry_hash = SHA-256(previous_hash_bytes(32) || canonical_entry_bytes)
```

The `||` symbol means byte concatenation with no separator.

### Genesis hash

The first entry in the chain has no predecessor. Its `previous_hash` is the
**32-byte genesis hash: all bytes set to `0x00`**.

```
GENESIS_HASH = new byte[32]  // all zeros
```

This is a deterministic sentinel value. Any integrity check can confirm the
genesis entry references exactly 32 zero bytes.

### Canonical encoding

The canonical byte representation of a ledger entry for hashing is defined
below. Fields are written in this exact order with no separator bytes between
fields. All multi-byte integers use **big-endian** byte order.

| # | Field | Encoding |
|---|---|---|
| 1 | `sequence_no` | 8 bytes, signed big-endian int64 |
| 2 | `entry_id` | 4-byte big-endian int32 byte-length prefix + UTF-8 bytes |
| 3 | `transaction_id` | 4-byte big-endian int32 byte-length prefix + UTF-8 bytes |
| 4 | `account_id` | 4-byte big-endian int32 byte-length prefix + UTF-8 bytes |
| 5 | `currency_code` | 4-byte big-endian int32 byte-length prefix + UTF-8 bytes |
| 6 | `debit_minor` | 8 bytes, signed big-endian int64 |
| 7 | `credit_minor` | 8 bytes, signed big-endian int64 |
| 8 | `created_at` | 4-byte big-endian int32 byte-length prefix + UTF-8 bytes (ISO 8601 UTC, e.g. `2026-11-25T14:30:00Z`) |

**Length prefix** = the number of UTF-8 bytes in the string field (not the number of characters). The length is written as a 4-byte big-endian signed int32 immediately before the UTF-8 byte sequence.

**No trailing bytes, padding, or null terminators.**

#### Canonical encoding example (pseudo-code)

```java
ByteArrayOutputStream buf = new ByteArrayOutputStream();
DataOutputStream out = new DataOutputStream(buf);

out.writeLong(entry.sequenceNo());                        // field 1 — 8 bytes

writeString(out, entry.entryId());                        // field 2
writeString(out, entry.transactionId());                  // field 3
writeString(out, entry.accountId());                      // field 4
writeString(out, entry.currencyCode());                   // field 5

out.writeLong(entry.debitMinor());                        // field 6 — 8 bytes
out.writeLong(entry.creditMinor());                       // field 7 — 8 bytes

writeString(out, entry.createdAt());                      // field 8

byte[] canonical = buf.toByteArray();

// Helper
void writeString(DataOutputStream out, String s) throws IOException {
    byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
    out.writeInt(bytes.length);   // 4-byte big-endian length prefix
    out.write(bytes);
}
```

### Entry hash computation

```java
MessageDigest sha256 = MessageDigest.getInstance("SHA-256");
sha256.update(previousHashBytes);    // exactly 32 bytes
sha256.update(canonicalEntryBytes);  // variable-length canonical encoding
byte[] entryHash = sha256.digest();  // 32 bytes
```

### HMAC-SHA-256 checkpoint

The integrity checkpoint provides an authenticated summary of the entire chain
state. It is recomputed and updated atomically inside every `BEGIN IMMEDIATE`
transaction that appends ledger entries.

**Checkpoint input (exact byte order, big-endian, no separator):**

| # | Field | Encoding |
|---|---|---|
| 1 | `last_entry_sequence` | 8 bytes, signed big-endian int64 |
| 2 | `chain_head_hash` | 32 bytes (the `entry_hash` of the most recent entry) |

**Checkpoint computation:**

```
checkpoint_value = HMAC-SHA-256(LEDGER_HMAC_SECRET_bytes, checkpoint_input)
```

The result is 32 bytes stored in the `IntegrityCheckpoint` table row.

### External secret handling

| Attribute | Value |
|---|---|
| Algorithm | HMAC-SHA-256 |
| Minimum key length | 32 bytes (256 bits) |
| Environment variable | `LEDGER_HMAC_SECRET` |
| Encoding | Base64 (standard alphabet, no line breaks) |
| At startup | Decode from env var; fail fast if absent or shorter than 32 bytes |
| Storage | Never written to the database, log files, or source code |
| In tests | Use a test-only placeholder (e.g. 32 zero bytes); never commit a real key |

> **CAUTION:** If `LEDGER_HMAC_SECRET` is absent at startup the application
> must refuse to start. A missing or weak secret renders the checkpoint
> meaningless.

### Atomic update

The `IntegrityCheckpoint` row is updated inside the **same `BEGIN IMMEDIATE`
transaction** that inserts the `LedgerEntry` rows. The checkpoint is never
stale relative to the committed chain.

```sql
-- Within BEGIN IMMEDIATE:
INSERT INTO ledger_entries (...) VALUES (...);   -- new entry

UPDATE integrity_checkpoints                      -- atomic checkpoint update
SET    last_entry_sequence = ?,
       chain_head_hash     = ?,
       checkpoint_mac      = ?,
       updated_at          = ?
WHERE  checkpoint_id       = 1;
```

There is **one global `IntegrityCheckpoint` row** (`checkpoint_id = 1`).

### Integrity check procedure (FR-4.3)

1. Read all `ledger_entries` ordered by `sequence_no` ascending.
2. Starting from the genesis hash, recompute `entry_hash` for each entry
   using the canonical encoding and compare it to the stored `entry_hash`.
3. Report the `sequence_no` of the first mismatch found, or `"OK"` if no
   mismatch.
4. Additionally verify the checkpoint: recompute HMAC over `last_entry_sequence`
   and `chain_head_hash`; compare to `checkpoint_value` with constant-time
   equality.

## Consequences

| Positive | Negative |
|---|---|
| Deterministic canonical encoding — anyone can reproduce and verify the hash | Adding a field to `ledger_entries` after deployment invalidates the entire chain |
| SHA-256 is a standard, auditable algorithm | Checkpoint secret rotation requires recomputing all checkpoint values |
| HMAC checkpoint detects wholesale chain substitution | Each write transaction incurs one extra SHA-256 and one HMAC computation |
| External secret never persisted in DB | Env var must be managed securely in deployment |

## Alternatives considered

**JSON encoding instead of binary canonical encoding:** Human-readable but
non-deterministic (key order, whitespace). Rejected.

**Per-currency chains:** Complicates the global integrity check required by
FR-4.3. Rejected.

**SHA-512:** No material security benefit for this use case; SHA-256 is
sufficient and more widely understood. Rejected.

## Sign-off

All team members approved 2026-11-25. Status changed from APPROVAL-REQUIRED to Accepted.
