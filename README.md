# Financial Ledger & Digital Wallet API

A self-contained backend REST API for multi-currency digital wallets, idempotent transfers, double-entry ledger accounting, full transfer reversals, tamper-evident hash chaining, and ledger reconciliation. Built with Java 21, Spring Boot 4.1.2, JDBC, and SQLite.

> **Scope note:** This is a demonstration financial-ledger backend. It never holds or moves real money.

---

## Main Features

- **User Registration & Salted API Keys:** Unique case-insensitive usernames (`3–20` characters, `[A-Za-z0-9_]`) mapped to internal UUIDs; `flw_<userId>.<secret>` API keys with 256-bit `SecureRandom` secrets, salted SHA-256 storage, and constant-time verification (`MessageDigest.isEqual`).
- **Multi-Currency Wallets (`INR`, `USD`):** One wallet per user per currency, scale `2` (64-bit integer minor units only—no `float` or `double`), zero initial balance, username-based creation/lookup plus UUID-based lookup.
- **Admin Demo Funding:** Balanced double-entry `FUNDING` transactions debiting auto-initialized `SYSTEM_FUNDING_INR` / `SYSTEM_FUNDING_USD` accounts and crediting wallet accounts.
- **Idempotent & Concurrency-Safe Transfers:** Per-user, case-sensitive `Idempotency-Key` (`1–64` printable ASCII chars), `Idempotent-Replayed: true` replay header, `409 IDEMPOTENCY_CONFLICT` on payload mismatch, and SQLite `BEGIN IMMEDIATE` write locking before authoritative balance checks.
- **Full Transfer Reversals:** Owner-only `COMPLETED → REVERSED` workflow metadata update paired with a new compensating `REVERSAL` transaction and balanced ledger entries; original entries are never modified.
- **Ledger History Pagination:** Ascending chronological keyset pagination (`cursor` + `limit` default `20` / max `100` clamped, `nextCursor` or `null`), omitting internal hash fields.
- **Database-Level Immutability & Tamper Detection:** SQLite `BEFORE UPDATE` / `BEFORE DELETE` triggers on `ledger_entries` and `ledger_transactions`, global SHA-256 hash chain from a 32-byte zero genesis hash, and atomic `HMAC-SHA-256` integrity checkpoint.
- **Admin Verification Endpoints:** Read-only `GET /admin/ledger-integrity` and `GET /admin/reconciliation` running inside `BEGIN DEFERRED` consistent snapshots.

---

## Technology Stack

| Component | Version / Choice |
|---|---|
| Language | Java 21 |
| Framework | Spring Boot `4.1.2` (Web MVC, Security, Validation, Actuator, JDBC) |
| Database | SQLite via `org.xerial:sqlite-jdbc` `3.51.3.0` (WAL mode, no ORM) |
| Build Tool | Maven Wrapper (`./mvnw` / `.\mvnw.cmd`) |
| Testing | JUnit 5 + Spring MockMvc |
| API Specification | OpenAPI 3.0.3 (`docs/openapi.yaml`) |

---

## Prerequisites

- **Java 21** (JDK 21+)
- **Maven:** Not required globally—use the included Maven Wrapper (`.\mvnw.cmd` on Windows, `./mvnw` on Linux/macOS).

---

## Setup & Environment Variables

The application fails fast at startup if `LEDGER_HMAC_SECRET` is missing or shorter than 32 decoded bytes. Configure these environment variables before starting the server:

| Variable | Required | Description |
|---|---|---|
| `ADMIN_API_KEY` | Yes (for `/admin/**`) | Secret key expected in `X-Admin-API-Key` header |
| `LEDGER_HMAC_SECRET` | Yes (startup fails if missing) | Base64-encoded secret of **at least 32 bytes** (256 bits) used for `HMAC-SHA-256` checkpoint MACs |

Example (PowerShell — replace with your own local dev values, never commit real secrets):

```powershell
$env:ADMIN_API_KEY = "local-dev-admin-key"
$env:LEDGER_HMAC_SECRET = [Convert]::ToBase64String((1..32 | ForEach-Object { Get-Random -Minimum 0 -Maximum 256 }))
```

---

## Database Configuration

Configured in `src/main/resources/application.properties`:

- `spring.datasource.url=jdbc:sqlite:./data/financial_ledger.db` (parent directory `./data/` is created automatically at startup).
- `PRAGMA journal_mode = WAL;` enabled at initialization.
- `PRAGMA foreign_keys = ON;`, `PRAGMA synchronous = FULL;`, and `PRAGMA busy_timeout = 5000;` applied on every connection.
- Schema (`src/main/resources/schema.sql`), `SYSTEM_FUNDING_INR` / `SYSTEM_FUNDING_USD` accounts, and the genesis `integrity_checkpoints` row (`checkpoint_id = 1`) are initialized idempotently on startup.
- Write transactions use explicit `BEGIN IMMEDIATE` with `autoCommit = true` and bounded retry (`3` retries, `50 ms` wait) returning `503 Service Unavailable` with `Retry-After: 1` on sustained `SQLITE_BUSY` contention.

---

## Running the Application

```powershell
.\mvnw.cmd spring-boot:run
```

Verify the server is up:

```powershell
curl http://localhost:8080/health
# {"status":"UP"}
```

---

## Authentication

- **Public:** `GET /health`, `POST /users`
- **User endpoints:** Require `X-User-API-Key: flw_<userId>.<secret>`
- **Admin endpoints (`/admin/**`):** Require `X-Admin-API-Key: <ADMIN_API_KEY>`
- Invalid or missing credentials return `401 Unauthorized` with `{"error":{"code":"INVALID_API_KEY","message":"..."}}`.

---

## Implemented API Endpoints

| Method | Path | Auth | Description |
|---|---|---|---|
| `GET` | `/health` | None | Public liveness check (`{"status":"UP"}`) |
| `POST` | `/users` | None | Register user with unique username; returns `apiKey` once |
| `POST` | `/users/{username}/wallets` | `X-User-API-Key` | Create `INR` or `USD` wallet (balance `0`) |
| `GET` | `/users/{username}/wallets/{currencyCode}` | `X-User-API-Key` | Retrieve own wallet by username and currency |
| `GET` | `/wallets/{walletId}` | `X-User-API-Key` | Retrieve own wallet by UUID |
| `GET` | `/wallets/{walletId}/ledger-entries` | `X-User-API-Key` | Paginated ascending ledger history (`cursor`, `limit`) |
| `POST` | `/admin/funding` | `X-Admin-API-Key` | Fund wallet via balanced `SYSTEM_FUNDING` double entry |
| `POST` | `/transfers` | `X-User-API-Key` + `Idempotency-Key` | Execute idempotent wallet-to-wallet transfer |
| `GET` | `/transfers/{transferId}` | `X-User-API-Key` | Retrieve transfer (source or destination owner) |
| `POST` | `/transfers/{transferId}/reversal` | `X-User-API-Key` | Full, once-only reversal (original source owner) |
| `GET` | `/admin/ledger-integrity` | `X-Admin-API-Key` | Verify SHA-256 hash chain and HMAC-SHA-256 checkpoint |
| `GET` | `/admin/reconciliation` | `X-Admin-API-Key` | Reconcile currency totals, transactions, and wallet balances |

Full request/response schemas and error codes are defined in [docs/openapi.yaml](docs/openapi.yaml).

---

## Example Requests

### 1. Register User
```bash
curl -s -X POST http://localhost:8080/users \
  -H "Content-Type: application/json" \
  -d '{"username":"alice_01"}'
```

### 2. Create Wallet (by Username)
```bash
curl -s -X POST http://localhost:8080/users/alice_01/wallets \
  -H "X-User-API-Key: flw_<userId>.<secret>" \
  -H "Content-Type: application/json" \
  -d '{"currencyCode":"INR"}'
```

### 3. Admin Fund Wallet (Minor Units: ₹500.00 = 50000 paise)
```bash
curl -s -X POST http://localhost:8080/admin/funding \
  -H "X-Admin-API-Key: local-dev-admin-key" \
  -H "Content-Type: application/json" \
  -d '{"walletId":"<walletId>","amountMinor":50000,"currencyCode":"INR"}'
```

### 4. Idempotent Transfer
```bash
curl -s -X POST http://localhost:8080/transfers \
  -H "X-User-API-Key: flw_<userId>.<secret>" \
  -H "Idempotency-Key: order-1001" \
  -H "Content-Type: application/json" \
  -d '{"sourceWalletId":"<srcWalletId>","destinationWalletId":"<dstWalletId>","currencyCode":"INR","amountMinor":15000}'
```

### 5. Reverse Transfer
```bash
curl -s -X POST http://localhost:8080/transfers/<transferId>/reversal \
  -H "X-User-API-Key: flw_<userId>.<secret>"
```

### 6. Paginated Ledger History
```bash
curl -s "http://localhost:8080/wallets/<walletId>/ledger-entries?cursor=0&limit=20" \
  -H "X-User-API-Key: flw_<userId>.<secret>"
```

### 7. Admin Integrity & Reconciliation
```bash
curl -s http://localhost:8080/admin/ledger-integrity -H "X-Admin-API-Key: local-dev-admin-key"
curl -s http://localhost:8080/admin/reconciliation -H "X-Admin-API-Key: local-dev-admin-key"
```

---

## Running Tests

Tests run against an isolated test database (`./target/test-data/test_ledger.db` and `@TempDir` databases configured in `src/test/resources/application.properties`):

```powershell
.\mvnw.cmd test
```

If running in an offline or sandboxed Windows environment:

```powershell
.\mvnw.cmd -o "-Daether.syncContext.named.factory=noop" "-DargLine=-javaagent:C:\Users\hasin\.m2\repository\net\bytebuddy\byte-buddy-agent\1.18.15\byte-buddy-agent-1.18.15.jar" test
```

---

## Project Structure

```
├── AGENTS.md                        # AI agent instructions & approved ADRs
├── pom.xml                          # Maven build (Spring Boot 4.1.2, Java 21, sqlite-jdbc 3.51.3.0)
├── ai-sessions/                     # AI-Assisted SDLC session records
├── brain/                           # Second Brain (PARA notes, index, log, SRS, skills)
├── docs/
│   ├── architecture.md              # Architecture, ER diagram, sequence diagrams
│   ├── development-plan.md          # Staged implementation plan
│   ├── openapi.yaml                 # OpenAPI 3.0.3 specification
│   ├── traceability-matrix.md       # Requirements-to-code/test traceability
│   ├── adr/                         # Accepted ADRs (ADR-001 through ADR-006)
│   └── design/                      # Detailed user, database, transfer designs
└── src/
    ├── main/java/com/finledger/api/ # admin, config, db, exception, funding, health, ledger, security, transfer, user, wallet
    ├── main/resources/              # application.properties, schema.sql
    └── test/                        # JUnit 5 integration & core tests
```

---

## Design Decisions & Limitations

- **Accepted ADRs:** See `docs/adr/ADR-001` through `ADR-006` for SQLite/JDBC, API key format, `BEGIN IMMEDIATE` locking, SHA-256/HMAC-SHA-256 canonical encoding, `INR`/`USD` scale 2, and JSON contract decisions.
- **No cross-currency conversion:** Transfers require identical source and destination currencies.
- **Full reversals only:** Partial reversals are not supported (`FR-4.6`).
- **Single-node SQLite:** Suitable for local/single-instance deployment; a locally stored checkpoint cannot detect a full-file rollback if an attacker replaces the entire SQLite file with an older valid snapshot without an externally retained checkpoint.
- **Deferred items:** `TBD-04` (idempotency record retention policy) and `TBD-07` (demo-funding limits / test-data reset) remain deferred.
