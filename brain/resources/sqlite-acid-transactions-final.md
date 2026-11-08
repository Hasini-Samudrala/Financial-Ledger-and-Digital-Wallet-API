
---
date: 2026-11-07
tags:
  - financial-ledger
  - sqlite
  - acid
  - transactions
  - atomicity
source:
  - https://www.sqlite.org/atomiccommit.html
  - https://www.sqlite.org/lang_transaction.html
---

# SQLite ACID Transactions

## Summary

A transaction groups several database changes into one unit that either fully happens or does not happen at all. SQLite keeps this promise even if the program crashes or the power fails partway through. Developers control transactions with `BEGIN`, `COMMIT`, and `ROLLBACK`.

## Key Takeaways

- **All or nothing.** In a transaction, either every change is saved or none are. SQLite keeps this even if a crash or power failure interrupts a commit. After a crash, it undoes any half-finished transaction automatically the next time the database is opened.
- **`BEGIN`, `COMMIT`, `ROLLBACK`.** `BEGIN` starts a transaction by hand, `COMMIT` saves everything, and `ROLLBACK` cancels everything since `BEGIN`. Without `BEGIN`, SQLite starts a transaction for you and commits it as soon as the statement finishes. So two separate statements are two separate transactions, not one.
- **After an error, roll back explicitly.** Some errors (disk full, I/O error, out of memory, interrupted operation) may undo only the failing statement or the whole transaction, depending on the case. The docs suggest sending `ROLLBACK` yourself so the state is always the same. If SQLite already rolled back, `ROLLBACK` returns an error but does no harm. A transaction is also rolled back if the database connection closes before `COMMIT`.
- **Many readers, one writer.** Several read transactions can run at once, but only one write transaction at a time. A reader keeps seeing the database as it was when its transaction started. If a read transaction tries to write while another connection is already modifying the database, the write fails with `SQLITE_BUSY`. `BEGIN IMMEDIATE` starts a write transaction right away, and it can fail with `SQLITE_BUSY` if another writer is active. The default `BEGIN` waits until the database is first used.

## ACID in Plain Words

|Letter|Meaning|Covered by these pages?|
|---|---|---|
|**A**tomicity|All of a transaction's changes happen, or none do|Yes. This is the "atomic commit" the first page describes.|
|**C**onsistency|The database moves from one valid state to another, for example no money is created or lost|No. This is the general meaning. Neither page defines it.|
|**I**solation|Transactions running at the same time do not see each other's unfinished work|Partly. Readers keep a snapshot and only one writer runs at a time, but the pages do not use the word.|
|**D**urability|Once committed, a change survives a crash|Partly. SQLite flushes changes to disk before it commits, but the pages do not use the word.|

## Example: Alice transfers ₹500 to Bob

_This example is ours, not from the SQLite docs. The SQL is pseudocode._

A transfer is two changes: take ₹500 from Alice and give ₹500 to Bob. If each change were committed separately and a failure hit in between, Alice would be ₹500 short and Bob would have received nothing, so the money would disappear. Putting both changes in one transaction means Alice's debit is not saved until `COMMIT`.

```sql
BEGIN;
-- 1. debit Alice 500 rupees (50000 paise)
-- 2. credit Bob 500 rupees (50000 paise)
COMMIT;
-- if anything fails before COMMIT: ROLLBACK;
```

**If the system fails after debiting Alice but before crediting Bob:**

- **The app is still running and gets an error:** it issues `ROLLBACK`. Alice's balance goes back to its original value and nothing is saved.
- **The whole system crashes:** the commit never happened, so SQLite undoes the partial change automatically when the database is next opened.
- **Either way,** the result is as if the transfer never started, and it can be attempted again.

## Relevance to Financial Ledger

- **Preventing incomplete transfers (NFR: Transaction Integrity):** Both ledger entries of a transfer, the debit and the credit, must be written in one transaction and committed only after both succeed. The project statement we have does not list a numbered "Transaction Integrity" NFR. The closest items are TQ-2 (rollback tests on simulated failures) and the "ACID by Default" point. A good test is to force a failure after the first entry and check that nothing was saved.
- **FR-2 (Idempotent Transfers):** A transaction and idempotency solve different problems. A transaction makes one attempt all-or-nothing. Idempotency stops the same request from being processed twice. If a transfer commits but the client never gets the reply and retries, a transaction alone would run the transfer again. The SQLite pages do not cover idempotency, so this is our project reasoning.
- **FR-3 (Concurrent Balance Mutability):** Because only one write transaction runs at a time, two writes cannot happen at the same instant. But transactions do not solve every concurrency problem. Our code still has to handle `SQLITE_BUSY` and decide how to start transactions. See [[sqlite-wal-mode]] for readers and writers working together.

## Additional Interpretation

_Ours, not from the SQLite docs._

- **Keep the idempotency record and the ledger entries in the same transaction.** Then one cannot be saved without the other.
- **Do the balance check inside the same write transaction as the debit.** Starting it with `BEGIN IMMEDIATE` takes the write turn first, so the balance cannot change between the check and the debit.

## Source Limitations

- The Atomic Commit page describes SQLite's default rollback-journal mode. It says atomic commit also works in WAL mode, but through a different mechanism. If we enable WAL, the journal details on that page do not apply, though atomicity still does.
- The all-or-nothing guarantee depends on the operating system and disk honoring flush requests, which the page lists as an assumption.

## Related Notes

- [[sqlite-wal-mode]]
- [[double-entry-accounting]]