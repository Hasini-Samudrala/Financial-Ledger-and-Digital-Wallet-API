# ADR-003: SQLite WAL mode and BEGIN IMMEDIATE write locking

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** Team 404 (all members approved 2026-11-25)
- **SRS reference:** FR-3.1–FR-3.5, SRS §6.1 (WAL mode)
- **Research notes:** [[sqlite-wal-mode]], [[sqlite-acid-transactions-final]],
  [[race-conditions]], [[spring-transactional]]

---

## Context

SQLite permits only one writer at a time. The SRS requires (FR-3.2) that the
write lock be acquired **before** any balance check used to decide whether a
financial operation may proceed. Without this, two concurrent requests can both
read a stale balance, both decide the funds are sufficient, and both proceed,
resulting in overspending (the lost-update race condition; see [[race-conditions]]).

Spring's `@Transactional` annotation uses `BEGIN DEFERRED` by default, which
does not acquire the write lock until the first write statement. This creates
a window between the balance read and the first write where another writer can
commit. See [[spring-transactional]].

### sqlite-jdbc WAL-reset bug

The Xerial sqlite-jdbc library bundled SQLite versions 3.7.0 through 3.51.2
contain a known WAL corruption bug (WAL-reset bug). The fix ships in
SQLite ≥ 3.51.3. The dependency in `pom.xml` has been upgraded from
`3.50.3.0` (bundling SQLite 3.50.3, **affected**) to **`3.51.3.0`**
(bundling SQLite 3.51.3, **fixed**).

## Decision

### WAL mode — enabled once at database initialisation

```sql
PRAGMA journal_mode = WAL;
```

This is a persistent database-level setting. It enables concurrent readers
while the writer holds the write lock (WAL allows reads to proceed against the
last committed snapshot).

### Per-connection PRAGMAs — set on every new connection

```sql
PRAGMA foreign_keys = ON;
PRAGMA synchronous  = FULL;
PRAGMA busy_timeout = 5000;
```

| PRAGMA | Value | Rationale |
|---|---|---|
| `foreign_keys` | `ON` | Enforces referential integrity at the SQLite layer |
| `synchronous` | `FULL` | Guarantees data is on disk before each commit returns; prevents corruption on OS crash |
| `busy_timeout` | `5000` (ms) | SQLite retries internally for up to 5 s before returning `SQLITE_BUSY` to the application |

### Write-lock acquisition — BEGIN IMMEDIATE

All financial writes (balance checks followed by updates) **must** use
`BEGIN IMMEDIATE`. This acquires the write lock before any read in the
transaction, satisfying FR-3.2.

#### Critical: Xerial JDBC sequence for BEGIN IMMEDIATE

The Xerial sqlite-jdbc driver automatically issues `BEGIN` (deferred) when
`connection.setAutoCommit(false)` is called. Calling `BEGIN IMMEDIATE` after
that returns:

```
SQLITE_ERROR: cannot start a transaction within a transaction
```

**Correct sequence (keep autoCommit = true throughout):**

```java
// Obtain a Connection from the DataSource (autoCommit defaults to true)
try (Connection conn = dataSource.getConnection()) {
    // conn.setAutoCommit(false) is NOT called — that would issue BEGIN.
    try (Statement stmt = conn.createStatement()) {
        stmt.execute("BEGIN IMMEDIATE");   // acquires write lock now
    }
    try {
        // All reads and writes via PreparedStatement on conn
        // e.g. check balance, insert LedgerEntry, update Wallet.balance_minor
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("COMMIT");
        }
    } catch (Exception ex) {
        try (Statement stmt = conn.createStatement()) {
            stmt.execute("ROLLBACK");
        }
        throw ex;
    }
}
```

> **Do not use `@Transactional`** for the `BEGIN IMMEDIATE` code path. Spring's
> transaction proxy calls `setAutoCommit(false)`, which causes the driver to
> issue `BEGIN` before our `BEGIN IMMEDIATE`, producing an error.
> Use the explicit JDBC sequence above wrapped in a helper method or a
> `TransactionTemplate` override.

Read-only endpoints (e.g. wallet balance, ledger history) use auto-commit mode
(each individual statement is its own implicit transaction). They do **not**
need `BEGIN IMMEDIATE`.

### Application-level retry and 503 response

When `BEGIN IMMEDIATE` encounters a locked database despite `busy_timeout`,
the driver throws `SQLiteException` with error code `SQLITE_BUSY` (5).
The application applies bounded retry before returning an error:

| Parameter | Value |
|---|---|
| Max retries | 3 |
| Wait between retries | 50 ms |
| Response after exhaustion | `503 Service Unavailable` |
| `Retry-After` header value | `1` (second) |

Retry pseudo-code:

```java
int attempt = 0;
while (true) {
    try {
        return executeWithBeginImmediate(work);
    } catch (SQLiteException ex) {
        if (ex.getResultCode() == SQLiteErrorCode.SQLITE_BUSY && attempt < 3) {
            attempt++;
            Thread.sleep(50);
        } else {
            throw ex; // let the controller map to 503
        }
    }
}
```

### sqlite-jdbc version

| Attribute | Value |
|---|---|
| Dependency | `org.xerial:sqlite-jdbc` |
| Previous version | `3.50.3.0` (SQLite 3.50.3 — WAL-reset bug present) |
| **Accepted version** | **`3.51.3.0`** (SQLite 3.51.3 — WAL-reset bug fixed) |

## Consequences

| Positive | Negative |
|---|---|
| `BEGIN IMMEDIATE` satisfies FR-3.2 exactly — write lock before balance check | Cannot use `@Transactional` for financial operations; requires explicit JDBC |
| WAL mode allows concurrent reads during write | WAL mode uses slightly more disk space (WAL file) |
| `synchronous=FULL` protects against OS-crash corruption | Marginally slower commits vs. `NORMAL` |
| Bounded retry + 503 gives callers a clear signal to back off | 3 retries × 50 ms + 5 s busy_timeout means worst-case ~5.15 s before 503 |

## Alternatives considered

**`DataSourceConnectionProxy` / `setAutoCommit` override:** Intercept
`setAutoCommit(false)` to emit `BEGIN IMMEDIATE` instead. More transparent to
Spring but requires a custom proxy class and careful testing. Deferred to
Stage 4 implementation; the explicit JDBC helper is simpler and equally correct.

**`@Transactional(isolation = SERIALIZABLE):`** SQLite ignores isolation level
hints via JDBC; the actual mode is determined by the `BEGIN` variant used.
Not applicable.

## Sign-off

All team members approved 2026-11-25. Status changed from APPROVAL-REQUIRED to Accepted.
