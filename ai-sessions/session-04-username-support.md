# Session 04 – Username Support

- **Date:** 2026-11-26
- **AI Tool / Model:** Antigravity AI coding assistant (exact model version not recorded in repository logs)
- **Prompt Summary (reconstructed):** Add unique case-insensitive usernames (`3–20` alphanumeric/underscore chars) to user registration and provide username-based wallet creation (`POST /users/{username}/wallets`) and balance lookup (`GET /users/{username}/wallets/{currencyCode}`), while retaining internal UUIDs for all DB and financial operations.
- **Requirements Addressed:** `FR-1.1`–`FR-1.3` (enhanced), `NFR-5`, `NFR-7`, `SE-2`, `SE-3`, `SE-5`

## Files Changed
- Code/Schema: `schema.sql` (`username TEXT NOT NULL`, `uq_users_username_lower`), `UserRecord`, `UserRepository`, `UserService`, `UserController`, `WalletRepository`, `WalletService`, `WalletController`
- Tests: `ApiMilestoneIntegrationTest.java`, `TransferAndConcurrencyIntegrationTest.java`
- Docs: `ADR-002`, `ADR-006`, `architecture.md`, `database-ledger-design.md`, `traceability-matrix.md`, `development-plan.md`, `AGENTS.md`, `brain/projects/srs-requirements-notes.md`, `brain/log.md`

## Design Decisions
- Preserved `user_id` UUID as primary key and API key lookup handle; added `uq_users_username_lower` index on `LOWER(username)` for case-insensitive uniqueness (`409 DUPLICATE_USERNAME`).
- Retained `GET /wallets/{walletId}` and UUID-based transfers unchanged.

## AI-Generated Work vs. Human Review
- **AI-generated:** Schema update, username validation/resolution, controller/service updates, integration test updates, and doc alignment.
- **Human verification:** Verified locally via JUnit test suite.

## Tests Executed & Actual Results
- `.\mvnw.cmd -o ... test` (with fresh `target/test-data`): `BUILD SUCCESS`, `Tests run: 18, Failures: 0, Errors: 0, Skipped: 0`.

## Outcome & Follow-up
- **Outcome:** Username support implemented, locally tested, and documented.
- **Follow-up:** Implement transfer reversals and paginated ledger history.
