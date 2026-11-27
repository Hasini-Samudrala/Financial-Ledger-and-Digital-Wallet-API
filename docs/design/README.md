# Design Documents

This folder contains the technical design documents for the Financial Ledger
and Digital Wallet API. All designs are based on SRS v1.3.

| Document | Scope | Status |
|---|---|---|
| [user-wallet-design.md](user-wallet-design.md) | User registration, API key auth, wallet creation and retrieval, ledger history | Accepted — see ADR-002, ADR-005, ADR-006 |
| [database-ledger-design.md](database-ledger-design.md) | SQLite schema, double-entry ledger, hash chain, reconciliation | Accepted — see ADR-001 through ADR-005 |
| [transfer-and-concurrency-design.md](transfer-and-concurrency-design.md) | Transfer flow, idempotency, concurrency and reversals | Accepted — see ADR-003, ADR-005, ADR-006 |

## Approved decisions

See `docs/adr/` for Architecture Decision Records (ADR-001 through ADR-006), all
of which are **Accepted** as of 2026-11-25.

## Related documents

- Architecture overview: `docs/architecture.md`
- ADRs: `docs/adr/`
- Development plan: `docs/development-plan.md`
- Traceability matrix: `docs/traceability-matrix.md`
- SRS: `brain/srs/Financial_Ledger_Digital_Wallet_SRS_v1.3.pdf`
