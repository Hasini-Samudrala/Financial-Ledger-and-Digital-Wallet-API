# Changelog

All notable local milestones for the **Financial Ledger & Digital Wallet API** are documented below.
No Git release tags or published versions exist yet; GitHub collaboration and CI/CD are pending team setup.

## Unreleased (Local Implementation Complete — Stages 1–6 & Local Stage 7)

### Added
- **Foundation & Database:** SQLite WAL-mode initialization (`schema.sql`), `PRAGMA foreign_keys = ON`, `synchronous = FULL`, `busy_timeout = 5000`, auto-created `SYSTEM_FUNDING_INR` / `SYSTEM_FUNDING_USD` accounts, genesis `integrity_checkpoints` row, and explicit `BEGIN IMMEDIATE` transaction executor with bounded `SQLITE_BUSY` retry (`503 Retry-After: 1`).
- **Authentication & Users:** `POST /users` with unique case-insensitive usernames (`3–20` `[A-Za-z0-9_]`), internal UUID primary keys, `flw_<userId>.<secret>` API keys (256-bit `SecureRandom`, salted SHA-256, `MessageDigest.isEqual`), and `X-User-API-Key` / `X-Admin-API-Key` security filter.
- **Wallets & Funding:** `POST /users/{username}/wallets`, `GET /users/{username}/wallets/{currencyCode}`, `GET /wallets/{walletId}`, and `POST /admin/funding` with balanced double-entry ledger records.
- **Transfers & Idempotency:** `POST /transfers` and `GET /transfers/{transferId}` with per-user case-sensitive `Idempotency-Key`, `Idempotent-Replayed: true` replay, `409 IDEMPOTENCY_CONFLICT`, `409 INSUFFICIENT_FUNDS`, and concurrent overdraft protection.
- **Reversals & Ledger History:** `POST /transfers/{transferId}/reversal` (full, once-only compensating `REVERSAL` transaction) and `GET /wallets/{walletId}/ledger-entries` (ascending keyset pagination, default 20 / max 100).
- **Ledger Integrity & Reconciliation:** `GET /admin/ledger-integrity` (SHA-256 hash chain + HMAC-SHA-256 checkpoint verification) and `GET /admin/reconciliation` (consistent `BEGIN DEFERRED` snapshot).
- **Documentation & Deliverables:** `README.md`, `docs/openapi.yaml`, `docs/architecture.md`, `docs/adr/ADR-001`–`ADR-006`, `docs/development-plan.md`, `docs/traceability-matrix.md`, `ai-sessions/`, Second Brain stage reviews, defect log, and skill evaluations.

### Fixed
- Upgraded `org.xerial:sqlite-jdbc` from `3.50.3.0` to `3.51.3.0` to resolve the SQLite WAL-reset bug.
- Corrected insufficient-funds HTTP status from `422` to `409` per SRS v1.3.
- Resolved broken Second Brain wikilinks and updated Agent Skill frontmatter and endpoint paths.

### Pending (Team / GitHub Stage)
- Git repository initialization, branching, GitHub Issues, pull requests, team reviews, GitHub Actions CI execution, and release tagging.
