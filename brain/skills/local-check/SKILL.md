---
name: local-check
description: Runs a consistent local build, test, and health-check verification of the Financial Ledger and Digital Wallet API before a pull request is submitted.
---

# Local Check

## Purpose

Run a consistent local verification of the Financial Ledger & Digital Wallet API before changes are submitted through a pull request.

## Instructions

When asked to run a local check:

1. Inspect the project structure and identify the build tool and available test commands.
    
2. Check that the required Java version is installed.
    
3. Run the Maven build and automated tests.
    
4. Report any compilation errors or test failures clearly.
    
5. Start the Spring Boot application if the project is ready to run.
    
6. Check the application's read-only health endpoint at `GET /health` as specified in SRS §3.3. If the endpoint is not yet implemented, check `/actuator/health` as a build-only proxy and note it as not yet compliant with the SRS.
    
7. Confirm whether the application starts and responds successfully.
    
8. Do not modify source code just to make a check pass without explaining the change.
    
9. Never perform destructive operations or send real financial transactions during verification.
    
10. Never expose API keys, passwords, or other secrets in logs or reports.
    

## Important Project Checks

As the project develops, verify:

- Database connection and SQLite initialisation.
    
- Application startup.
    
- Health endpoint availability.
    
- Automated unit and integration tests.
    
- Transaction rollback behaviour where tests are available.
    
- No unintended changes to the database or existing data.
    

## Current Project Status

Stages 1–6 (users with usernames, wallets, admin funding, idempotent transfers, reversals, paginated ledger history, ledger integrity, and reconciliation) are implemented and tested locally.

External GitHub CI verification is deferred until the team pushes the repository.

## Research References

Use these notes when relevant:

- [[integration-testing-and-rollback]]
    
- [[sqlite-acid-transactions-final]]
    
- [[spring-transactional]]
    

## Output Format

After running the checks, provide:

- **Build:** Pass or Fail.
    
- **Tests:** Pass or Fail, including the number of tests if available.
    
- **Application startup:** Pass, Fail, or Not Checked.
    
- **Health endpoint:** Pass, Fail, or Not Implemented.
    
- **Issues:** Errors or warnings found.
    
- **Next steps:** What needs to be fixed.
    

## Safety Rules

- Do not delete project files or databases.
    
- Do not use production credentials.
    
- Do not perform real wallet transfers.
    
- Do not claim a check passed unless it was actually executed.
    
- Ask before making significant changes to project configuration.
    

## Quality Check

Before finishing, make sure every reported result is based on an actual command or test execution.