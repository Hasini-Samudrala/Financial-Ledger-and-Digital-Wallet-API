# Stage Review Notes

This file separates the **Official SDLC Stages** (from `SDLC_and_Workflow.pdf` §7)
from the **Implementation Milestones** (from `docs/development-plan.md`).

> **Evidence note:** Reviews below are grounded in `brain/log.md`, `ai-sessions/`,
> `brain/projects/defect-log.md`, and executed local tests. GitHub/CI and final
> team review items remain pending.

---

## Part 1 — Official SDLC Stage Reviews (`SDLC_and_Workflow.pdf` §7)

### SDLC Stage 1 — Requirements (`Complete — Local`)
- **Scope:** SRS v1.3 (`FR`, `NFR`, `SK`), Second Brain PARA vault (`index.md`, `log.md`), `brain-ingest` + 10 research notes, `AGENTS.md`, repository & CI skeleton.
- **What worked:** SRS v1.3 established clear requirement IDs (`FR-1`–`FR-5`, `NFR-1`–`NFR-7`, `BR-1`–`BR-5`), and the 10 research notes provided concrete technical grounding.
- **What did not work:** Initial `brain-refine`, `brain-query`, and `local-check` skills lacked YAML frontmatter, and several research notes contained broken `sqlite-acid-transactions` wikilinks.
- **What changed / improved:** Fixed Skill frontmatter, corrected broken wikilinks to `[[sqlite-acid-transactions-final]]`, and created root `AGENTS.md`.

### SDLC Stage 2 — Design (`Complete — Local`)
- **Scope:** Architecture & design docs (`docs/architecture.md`, `docs/design/*.md`), ER & sequence diagrams, ADRs (`ADR-001`–`ADR-006`), `brain-refine`, `brain-query`, `local-check`.
- **What worked:** Resolving all 5 blocking ADRs (`ADR-002`–`ADR-006`) before coding eliminated schema and contract ambiguity (`user_id` lookup, `BEGIN IMMEDIATE` `autoCommit=true` sequence, canonical binary encoding, `INR`/`USD` scale 2, JSON contracts).
- **What did not work:** Early drafts had a 4-column `users` conflict (`api_key_id`), `422` vs `409` status mismatch for insufficient funds, `ADR-004` checkpoint column mismatch (`checkpoint_value`/`GLOBAL`), `ADR-006` path mismatches, and `sqlite-jdbc:3.50.3.0` WAL-reset exposure.
- **What changed / improved:** Upgraded `sqlite-jdbc` to `3.51.3.0`, aligned all schemas/paths/status codes, and marked `user-wallet-design.md` alignment notes.

### SDLC Stage 3 — Testing and CI (`In Progress — Local Testing Complete; GitHub CI Pending`)
- **Scope:** Continuous testing, CI plan/results, product skill (`simulate-transfer`) with test results, defect log with regression tests, stage review notes.
- **What worked:** JUnit 5 suite grew to 33 passing tests covering core DB/triggers, API auth/ownership/username rules, concurrent idempotency & overdrafts, real `TransferService` workflow rollback, reversals, keyset pagination, and `@TempDir` integrity/reconciliation tampering checks. Live HTTP + SQLite trigger verification passed 17/17 checks in Session 05.
- **What did not work:** Stale `data/financial_ledger.db` (pre-username 3-column `users` schema) remained in the working directory until backed up and removed; `simulate-transfer` had stale `/users/{userId}/wallets` paths.
- **What changed / improved:** Backed up and removed stale `data/financial_ledger.db`, updated `simulate-transfer` to all 14 PDF steps, and added `defect-log.md` and `skill-evaluations.md`. GitHub Actions CI remains pending team GitHub setup.

### SDLC Stage 4 — Review (`Pending — Not Yet Executed`)
- **Scope:** Live demonstration of FRs in client, end-to-end `simulate-transfer` live run, live `local-check` & `brain-query`, requirement walkthrough (brain note → ADR → code → test), final release tag.
- **Status:** Not yet started; intentionally deferred until team GitHub collaboration and final demo.

---

## Part 2 — Implementation Milestone Notes (`docs/development-plan.md`)

- **Dev Plan Stages 1–2 (Foundation, Auth, Wallets, Funding, Ledger Core):** Implemented and verified locally; fixed Jackson 3 (`tools.jackson.databind`) imports for Spring Boot 4.1.2.
- **Dev Plan Stages 3–4 (Transfers, Idempotency, Concurrency):** Implemented and verified with multi-threaded concurrency tests; later strengthened with `TransferWorkflowRollbackAndUnitTest` for full-workflow rollback and fingerprint/balance unit tests.
- **Username Enhancement:** Added case-insensitive unique `username` (`3–20` `[A-Za-z0-9_]`) and username wallet routes while keeping internal UUIDs intact.
- **Dev Plan Stages 5–6 (Reversals, Ledger History, Integrity, Reconciliation):** Implemented and verified via JUnit and live disposable-DB HTTP/SQLite checks.
- **Dev Plan Stage 7 (Local Deliverables):** Completed `README.md`, `docs/openapi.yaml`, `ai-sessions/`, `CHANGELOG.md`, `.gitignore`/`.env.example`, and Second Brain alignment; GitHub/CI items deferred by team.
