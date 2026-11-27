# ADR-001: SQLite as the database

- **Status:** Accepted
- **Date:** 2026-11-19
- **Deciders:** Team 404
- **SRS reference:** §2.5 (Design and Implementation Constraints), §6.1 (Monetary Representation and Database)

---

## Context

The SRS v1.3 specifies the database technology directly. We document the
reasoning here as a formal decision record.

## Decision

Use **SQLite** accessed via **JDBC** (no ORM) as the sole data store.

Enable **WAL mode** once at database creation.

Enable **foreign key enforcement** on every connection.

Store all monetary values as **64-bit signed integer minor units** (never
`FLOAT`, `REAL`, or `DOUBLE`).

Store UUIDs as `TEXT` and timestamps as `TEXT` in ISO 8601 UTC format.

Store cryptographic hashes and MACs as `BLOB`.

## Rationale

- SRS §2.5 and §6.1 mandate SQLite and WAL mode explicitly.
- SQLite provides ACID transactions sufficient for a single-server demo system.
- WAL mode allows concurrent readers during writes without blocking.
- JDBC with explicit SQL gives direct control over `BEGIN IMMEDIATE` for write
  serialisation (see ADR-003).
- Integer minor units eliminate floating-point rounding errors
  (see [[monetary-precision]]).

## Consequences

- The application runs on a single server. SQLite does not support
  multi-server clustering.
- Write throughput is limited by SQLite's single-writer model. For a demo
  system, this is acceptable.
- The team must handle `SQLITE_BUSY` explicitly (see ADR-003).
- The `data/` directory and `*.db`, `*.db-shm`, `*.db-wal` files must be
  excluded from version control (already in `.gitignore`).

## References

- [[sqlite-acid-transactions-final]]
- [[sqlite-wal-mode]]
- [[monetary-precision]]
