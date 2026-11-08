---
date: 2026-11-07
tags:
  - race-conditions
  - sqlite
  - locking
  - transactions
  - wal-mode
  - fr-3
source:
  - "https://www.sqlite.org/isolation.html"
  - "https://www.sqlite.org/lang_transaction.html"
---
# Race Conditions and SQLite Locking

## Summary

A race condition happens when two requests touch the same data at nearly the same time and the result depends on which one gets there first. For a wallet, this can mean spending money that isn't there. SQLite lets many connections read at once but only one write at a time, and it reports contention with errors such as `SQLITE_BUSY`. Transactions, careful locking and checked updates keep balances correct, but our code still has to use them properly.

## Key Takeaways

- **One writer at a time.** SQLite allows many simultaneous read transactions but only one write transaction. Multiple connections can write to the same database, but they take turns, and SQLite uses locks to enforce this automatically. _(SQLite: Transaction, Isolation)_
- **A transaction groups statements and hides unfinished work.** Changes made on one connection are invisible to other connections until the transaction commits. A read transaction also keeps seeing an older snapshot of the database, even if someone else commits changes while it is open. _(SQLite: Transaction, Isolation)_
- **WAL mode lets readers and a writer work at the same time, but still only one writer.** WAL has to be switched on with `PRAGMA journal_mode=WAL`, because the default is the older rollback-journal mode. _(SQLite: Isolation)_
- **A read that later turns into a write can fail.** If a transaction starts with a read and then tries to write, but another connection has already changed or is changing the database, the write fails with `SQLITE_BUSY`. In WAL mode the stale-snapshot case produces `SQLITE_BUSY_SNAPSHOT`. `BEGIN IMMEDIATE` avoids this by starting the write transaction up front. If it succeeds, later statements in that transaction won't fail with `SQLITE_BUSY`. _(SQLite: Transaction, Isolation)_
- **`SQLITE_BUSY` is something to expect and handle.** It appears when a connection can't get the access it needs because another connection is in the way. For example, `BEGIN IMMEDIATE` can fail if another write transaction is active, and a `COMMIT` can fail while another connection has a read open. The docs say a failed `COMMIT` leaves the transaction active so it can be retried later. _(SQLite: Transaction)_

## Relevance to Financial Ledger

This is the core of **FR-3 (Concurrent Balance Mutability)**: many simultaneous transfers on one wallet must never corrupt a balance.

### Example: Alice has ₹1,000, and two requests arrive together

Request A sends ₹700 and Request B sends ₹600. _This explanation is ours, not from the docs._

**Unsafe flow** (checking and deducting as separate, unprotected steps):

1. A reads Alice's balance: ₹1,000. Enough for ₹700, so it passes.
2. B reads Alice's balance: ₹1,000. Enough for ₹600, so it passes.
3. A deducts ₹700 and B deducts ₹600.

Both checks used the same old balance, so both requests were allowed. What happens next depends on how the update is written:

- **If both deductions are applied,** Alice's balance becomes ₹1,000 − ₹700 − ₹600 = **−₹300**. She has spent more than she had.
- **If each request overwrites the balance using the stale value it read** (A writes ₹300, B writes ₹400), one update replaces the other. This is called a _lost update_. Alice ends at ₹300 or ₹400, depending on which write lands last. Only one deduction is recorded, yet ₹1,300 was sent out, so money has been created from nothing.

Either way, the result is wrong.

**Correct result:** one request succeeds, the other fails with "insufficient funds", and Alice ends with ₹300 or ₹400 depending on which went first. The balance must never go below zero.

### What SQLite documents, and what we should do

**SQLite documents these behaviors:**

- Only one write transaction can exist at a time.
- A read-then-write transaction that falls behind another writer fails with `SQLITE_BUSY` (or `SQLITE_BUSY_SNAPSHOT` in WAL mode). The docs explain that allowing the write would fork the database history, which SQLite does not support.
- `BEGIN IMMEDIATE` starts the write transaction immediately, blocking other writers, so the check and the update happen while we hold the write lock.

**Our implementation recommendations (not from the docs):**

- Run each transfer in one transaction started with `BEGIN IMMEDIATE`, not the default `BEGIN DEFERRED`.
- Use a conditional update so the balance check and the deduction are a single statement:

```sql
BEGIN IMMEDIATE;
UPDATE wallets SET balance = balance - 70000      -- ₹700 in paise
 WHERE id = :alice AND balance >= 70000;          -- 0 rows changed = insufficient funds
-- if 1 row changed: credit Bob, insert both ledger entries
COMMIT;
```

- Handle `SQLITE_BUSY` explicitly. Retry briefly, then return a clear error. Any retry must reuse the same idempotency key (FR-2) so it can't double-charge.
- Keep write transactions short, since every other writer waits for them.
- Add a concurrency test that fires the Alice ₹700 and ₹600 requests together and asserts the balance never goes negative (TQ-4).

### What does not come for free

Transactions and WAL mode do not fix race conditions by themselves. A transaction that reads a balance, decides in application code, and then writes can still act on stale data. WAL also does not allow two simultaneous writers.

## Additional Interpretation

_Our own reasoning, not stated in the docs:_

- Because a WAL read transaction sees a fixed snapshot, a balance read earlier in a transaction can already be out of date. That is another reason to do the check and update in one conditional statement under `BEGIN IMMEDIATE`.
- A busy error means the transfer did not happen. The API should say so clearly, so a client retry with the same idempotency key is safe.

## Source Limitations

- The two SQLite pages describe locking and transaction behavior. They do not define "race condition" and say nothing about wallets, conditional updates, or how to design retries. Those parts are our reasoning.
- Busy timeouts and retry strategies are not covered by these pages, so the retry advice above should be checked against SQLite's other documentation before we settle on an approach.

## Related Notes

- [[sqlite-wal-mode]]
- [[api-idempotency-keys]]
- [[integration-testing-and-rollback]]