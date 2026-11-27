# AGENTS.md — Financial Ledger & Digital Wallet API

This file is the authoritative instruction set for all AI coding agents working
in this repository. Read it before every session. The Second Brain at
`brain/index.md` is the companion knowledge base.

---

## Project summary

Backend REST API simulating a digital wallet for a software engineering course.
The system never holds real money. It is built with Java 21, Spring Boot 4.1.2,
and SQLite (accessed through JDBC).

The authoritative requirements are in
`brain/srs/Financial_Ledger_Digital_Wallet_SRS_v1.3.pdf`.

---

## Non-negotiable rules

1. **Never store money as `float` or `double`.** Use 64-bit integer minor units
   (for example, paise for INR). This is SRS §6.1 and an AGENTS rule.

2. **Never update or delete a committed ledger entry.** Corrections use
   compensating reversal transactions. This is FR-4.1.

3. **Every balance change is one database transaction.** The write lock is
   acquired with `BEGIN IMMEDIATE` before any balance check. This is FR-3.2 and
   ADR-003.

4. **Never store, log, or expose an API key in plaintext.** Only a salted hash
   and its salt are stored. The key is returned once on registration. This is
   NFR-6.

5. **Never commit secrets or credentials.** The admin key and the HMAC secret
   come from environment variables or a local `.env` file that is in `.gitignore`.

6. **Use parameterised SQL for every query.** No string concatenation with
   user-supplied values. This is NFR-5.

7. **Every change goes through a pull request reviewed by a teammate.**
   Every AI-generated change must be reviewed by a human before merging.

8. **Never commit directly to `main`.** Work on a feature branch; open a PR.

---

## Architecture summary

```
Client → GET /health                (public, SRS §3.3)
       → POST /users                (public, no auth)
       → X-User-API-Key header      (all wallet, transfer, ledger endpoints)
       → X-Admin-API-Key header     (POST /admin/funding,
                                     GET /admin/reconciliation,
                                     GET /admin/ledger-integrity)
```

Layers: Controller → Service → Repository → SQLite (JDBC, no ORM).

Full architecture: `docs/architecture.md`.

---

## Key file locations

| What | Where |
|---|---|
| SRS (requirements baseline) | `brain/srs/Financial_Ledger_Digital_Wallet_SRS_v1.3.pdf` |
| Architecture overview | `docs/architecture.md` |
| Database and ledger design | `docs/design/database-ledger-design.md` |
| User, auth and wallet design | `docs/design/user-wallet-design.md` |
| Transfer and concurrency design | `docs/design/transfer-and-concurrency-design.md` |
| ADRs | `docs/adr/` |
| Development plan | `docs/development-plan.md` |
| Traceability matrix | `docs/traceability-matrix.md` |
| Agent Skills | `brain/skills/` |
| Research notes | `brain/resources/` |
| Activity log | `brain/log.md` |
| Second Brain index | `brain/index.md` |

---

## Endpoints

| Method | Path | Auth | FR |
|---|---|---|---|
| GET | `/health` | None | SRS §3.3 |
| POST | `/users` | None | FR-1.1 |
| POST | `/users/{username}/wallets` | X-User-API-Key | FR-1.2 |
| GET | `/users/{username}/wallets/{currencyCode}` | X-User-API-Key | FR-1.3 |
| GET | `/wallets/{walletId}` | X-User-API-Key | FR-1.3 |
| GET | `/wallets/{walletId}/ledger-entries` | X-User-API-Key | FR-4.7 |
| POST | `/admin/funding` | X-Admin-API-Key | FR-1.4 |
| POST | `/transfers` | X-User-API-Key | FR-2.1 |
| GET | `/transfers/{transferId}` | X-User-API-Key | FR-2.1 |
| POST | `/transfers/{transferId}/reversal` | X-User-API-Key | FR-4.5 |
| GET | `/admin/reconciliation` | X-Admin-API-Key | FR-5 |
| GET | `/admin/ledger-integrity` | X-Admin-API-Key | FR-4.3 |

---

## Approved decisions (ADR-001 through ADR-006)

All blocking design decisions have been approved and marked **Accepted** in `docs/adr/`:

- **ADR-001** SQLite + JDBC (no ORM), 64-bit integer minor units.
- **ADR-002** `flw_<userId>.<secret>` API key; 256-bit random secret; salted SHA-256 hash; constant-time comparison.
- **ADR-003** SQLite WAL (`sqlite-jdbc` 3.51.3.0+), `BEGIN IMMEDIATE` (autoCommit=true), `synchronous=FULL`, `busy_timeout=5000`, 3 retries × 50 ms, `503 Retry-After: 1`.
- **ADR-004** Global SHA-256 ledger hash chain, 32-byte zero genesis hash, canonical binary encoding, HMAC-SHA-256 checkpoint, 32-byte min `LEDGER_HMAC_SECRET`.
- **ADR-005** INR and USD only (scale 2); system funding accounts initialized automatically at startup.
- **ADR-006** JSON contracts, standard error envelope, ascending chronological ledger pagination (`limit` default 20 / max 100, exclusive `cursor`, `nextCursor`, no hash fields).

---

## Start here

1. Read `brain/index.md`.
2. Read the relevant design document and ADR for the feature you are working on.
3. Check the development plan (`docs/development-plan.md`) for the current stage.
4. Run `local-check` before and after every change.
5. Log significant AI sessions in `brain/log.md`.
