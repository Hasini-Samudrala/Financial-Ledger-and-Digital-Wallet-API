---
date: 2026-11-07
tags:
  - financial-ledger
  - sqlite
  - wal
  - concurrency
  - checkpointing
source:
  - "https://www.sqlite.org/wal.html (SQLite docs, Write-Ahead Logging)"
  - "https://www.sqlite.org/isolation.html (SQLite docs, Isolation In SQLite)"
---

# SQLite Write-Ahead Logging (WAL) Mode

## Summary
WAL is an alternative way for SQLite to save changes. Instead of editing the main database file directly, SQLite adds changes to the end of a separate log file, and a transaction counts as committed when a commit record is added to that log. This lets readers keep reading while one writer writes, but SQLite still allows only one writer at a time.

*Labels used below: **Official docs** means it comes from the two SQLite pages. **Ours** means our own example or project reasoning.*

## Key Takeaways
- **WAL changes where updates go first (official docs).** In the default rollback-journal mode, SQLite writes changes straight into the database file and keeps a backup copy of the old content. In WAL mode, the main database file stays untouched and changes are appended to a `-wal` file. See the comparison table below.
- **Readers and a writer can work at the same time (official docs).** When a reader starts, it remembers the latest commit in the WAL and sees the database as of that moment until its transaction ends. It never sees a half-finished transaction. Many readers can run at once.
- **There is still only one writer at a time (official docs).** There is a single WAL file, so writers take turns. SQLite uses locks to line them up automatically. WAL allows readers and a writer together. It does not allow several writers together.
- **Checkpointing moves WAL content back into the main database file (official docs).** Without it, the WAL would keep growing, and reads slow down as the WAL gets bigger. By default, SQLite checkpoints automatically when the WAL reaches about 1000 pages. A checkpoint can run while readers are active, but it cannot go past what an active reader still needs. If there is always at least one reader, the WAL can grow without bound.
- **WAL is not a complete solution (official docs).** Readers and writers do not block each other, but the docs say this is only mostly true. `SQLITE_BUSY` errors can still happen in some cases, such as when the last connection is closing or while crash recovery is running. All processes must be on the same computer, so WAL does not work over a network filesystem. A rare corruption bug affected WAL databases in versions 3.7.0 through 3.51.2 when two connections wrote or checkpointed at the same instant. It is fixed in 3.51.3, and the docs call it unlikely but recommend upgrading.

## Rollback Journal vs WAL
| | Rollback journal (default) | WAL mode |
|---|---|---|
| Where changes go first | Straight into the database file, with the old content saved in a journal | Appended to the `-wal` file |
| What commits a transaction | Deleting the journal | Adding a commit record to the WAL |
| Readers during a write | Locked out while the write is being written to disk | Keep reading the old content |
| Writers | One at a time | One at a time |
| Extra work | None | Checkpointing |

## Example: Bob checks his balance while Alice sends him money

Bob has ₹200. Alice is sending him ₹500.

1. Alice's transfer starts. The writer adds the debit and credit to the WAL, but has not committed yet.
2. Bob opens his wallet. His read is not blocked, and it shows ₹200, the last committed value. He never sees a half-done transfer.
3. The transfer commits when the writer adds a commit record to the WAL.
4. Bob's read, if it is still open, keeps showing ₹200. A new read after the commit shows ₹700.
5. If a second transfer tries to write at the same moment as the first, it has to wait for its turn.

In the default rollback mode, Bob's read could be held up while the write was being written to disk.

## Relevance to Financial Ledger
- **FR-3 (Concurrent Balance Mutability):** Many users will check balances while transfers are processed. WAL helps because a transfer in progress does not block balance reads, and each read sees a consistent committed state. Many readers at once is fine. Many writers at once is not possible, so simultaneous transfers are handled one after another.
- **NFR: Concurrency Safety:** The project statement we have does not list a numbered NFR with this name. The closest items are FR-3 and TQ-4 (simultaneous transfer tests that prove race safety).
- **WAL is only part of the answer:** Our application still needs proper transaction handling (see [[sqlite-acid-transactions]]) and error handling for `SQLITE_BUSY`. WAL makes reading and writing coexist but does not by itself make competing transfers safe.

## Additional Interpretation
*Ours, not from the docs.*
- **A balance read can be slightly out of date.** It shows the last committed state, which a transfer may be about to change. That is fine for display, but the "enough funds" check for a transfer should happen inside the write transaction. The isolation page says `BEGIN IMMEDIATE` starts a write transaction right away and blocks other writers.
- **Concurrency tests need separate connections.** The isolation page says there is no isolation between operations on the same connection, so TQ-4 tests should use a separate connection for each simulated user.
- **Choose the durability setting on purpose.** The WAL page says that with `PRAGMA synchronous=NORMAL`, a committed transaction might be rolled back after a power failure, while `FULL` syncs the WAL on every commit. For a ledger, that is a decision to make deliberately.

## Source Limitations
The two pages do not explain how long a waiting writer waits or how to set up retries and timeouts for `SQLITE_BUSY`. We need another source for that before finishing FR-3.

## Related Notes
- [[sqlite-acid-transactions]]
- [[double-entry-accounting]]