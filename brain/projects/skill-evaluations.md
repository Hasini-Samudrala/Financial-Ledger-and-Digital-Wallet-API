# Agent Skill Evaluation Evidence

Records genuine local skill checks/executions only. Skills not yet executed end-to-end are marked **Not yet evaluated**.

| Skill | Evaluation Scenario | Expected Result | Actual Result | Status | Observations / Follow-up |
|---|---|---|---|---|---|
| `local-check` | Build, run full JUnit suite, start Spring Boot on disposable DB (`target/smoke-test-data/smoke.db`, port `18080`), call `GET /health` | Maven tests pass; server starts; `GET /health` returns `200 {"status":"UP"}` | Observed in Session 05: 23 JUnit tests passed at that time; live server started on port 18080 and `GET /health` returned `200 UP` | **Pass (local)** | Re-run after Stage 6/7 docs if full live smoke check is needed before submission |
| `simulate-transfer` | 14-step end-to-end workflow (register, wallets, funding, transfer, replay, concurrent same-key, concurrent overdraft, reconciliation) on disposable DB | All 14 steps pass without overdraft or duplicate transfer | Core steps (register, fund, transfer, reversal, pagination, triggers) executed in Session 05 live smoke test; concurrent same-key, concurrent overdraft, and reconciliation verified via JUnit (`TransferAndConcurrencyIntegrationTest`, `AdminIntegrityAndReconciliationTest`), not yet executed as one combined live `simulate-transfer` run | **Partially verified (JUnit + partial live)** | Execute full 14-step `simulate-transfer` against a running disposable server before final team demo |
| `brain-ingest` | Structure & YAML frontmatter validation | Valid `name`/`description` frontmatter | Frontmatter validated via Python script | **Not evaluated end-to-end** | Used template rules only; no new ingestion run recorded |
| `brain-refine` | Structure & YAML frontmatter validation | Valid `name`/`description` frontmatter | Frontmatter validated; wikilink fixes applied in Session 02 & Audit | **Not evaluated end-to-end** | — |
| `brain-query` | Structure & YAML frontmatter validation | Valid `name`/`description` frontmatter | Frontmatter validated via Python script | **Not evaluated end-to-end** | — |
