---
date: 2026-11-07
tags:
  - integration-testing
  - rollback
  - junit5
  - spring-testing
  - transactions
  - tq-2
source:
  - "https://docs.spring.io/spring-framework/reference/testing/testcontext-framework.html"
  - "https://docs.spring.io/spring-framework/reference/testing/integration.html"
  - "https://docs.spring.io/spring-framework/reference/testing/testcontext-framework/tx.html"
  - "https://junit.org/junit5/docs/current/user-guide/"
---
# Integration Testing and Rollback

## Summary

A **unit test** checks one small piece of code on its own. An **integration test** checks that several real parts work together, for example our transfer service plus the actual database. For a wallet, the most important thing to prove is that when a transfer fails halfway, the database goes back to exactly how it was before. Only a test against a real database can prove that.

## Key Takeaways

- **Integration tests use real components together.** Spring describes them as tests of how components are wired together and how data access behaves, including whether the SQL is correct. They run without deploying to an application server. They are slower than unit tests but much faster than tests that need a full deployment. _(Spring: Integration Testing)_
- **Tests that touch a real database change its state.** Spring warns that even a development database can be affected by earlier tests, which then influences later ones. Many insert and update operations also can't be properly performed or checked outside a transaction. _(Spring: Integration Testing)_
- **Spring's test transactions roll back by default.** A test marked `@Transactional` runs inside a transaction that is rolled back when the test finishes, so the database is cleaned up automatically. `@Commit` changes this. _(Spring: Transaction Management)_
- **You can check state outside the transaction.** `@BeforeTransaction` and `@AfterTransaction` methods run outside the test's transaction. Spring suggests them for verifying the database state before the test and after it. _(Spring: Transaction Management)_
- **JUnit 5 is the framework that writes and runs the tests.** It has three parts: the Platform (the foundation that launches tests), Jupiter (the part we write tests with, such as `@Test`, `@BeforeEach` and `@AfterEach`), and Vintage (runs older JUnit 3/4 tests). IDEs and build tools such as Gradle and Maven can run these tests, which is what lets CI run them on every push. _(JUnit 5 User Guide)_

## Relevance to Financial Ledger

Our brief asks for integration tests on database transactions and rollback after simulated transfer failures (TQ-2), and for CI to use an isolated test database (TQ-5). Unit tests (TQ-1) check the maths and hashing. Integration tests check that the database really keeps its promises.

### Example: Alice sends Bob ₹500 (our own project example, not from the docs)

Amounts are in paise, per TQ-6. Alice starts with ₹1,000 (`100_000`) and Bob with ₹200 (`20_000`). The service debits Alice, then an error is forced before Bob is credited.

```java
@Test
void transferFails_whenErrorBeforeBobIsCredited_nothingIsCommitted() {
    long alice = createWallet("Alice", 100_000);
    long bob   = createWallet("Bob",    20_000);
    int ledgerRowsBefore = countLedgerRows();

    failOnNextCredit();   // test-only hook: throws after the debit step

    assertThrows(RuntimeException.class,
        () -> transferService.transfer(alice, bob, 50_000, "key-1"));

    assertEquals(100_000, balanceOf(alice));            // Alice restored
    assertEquals(20_000,  balanceOf(bob));              // Bob unchanged
    assertEquals(ledgerRowsBefore, countLedgerRows());  // no partial entries
}
```

The test checks four things, each read straight from the test database:

1. **The transfer fails.** The call throws (or the API returns an error in an end-to-end test).
2. **Alice's balance is restored** to ₹1,000.
3. **Bob's balance is unchanged** at ₹200.
4. **No incomplete ledger entries remain.** The number of ledger rows is the same as before, so the debit entry was not left behind without its matching credit.

### Why check the database, not just the return value

A service method can throw the right error and still leave Alice's debit committed. The return value looks correct, but the money has vanished. Only a query against the actual database shows whether the rollback happened.

### Link to the requirements

- **FR-2 (Idempotent Transfers):** after the failed transfer above, no record should say `key-1` succeeded. Retrying the same key should then be able to complete the transfer, not return a false "already done". Whether the idempotency record is written inside the same transaction is our design decision, and this test would check it.
- **FR-3 (Concurrent Balance Mutability):** Spring ties test-managed transactions to the current thread. Work done by other threads is not part of that transaction and is not rolled back with it. Concurrency tests will therefore commit real data and need a fresh database for each test.
- **ORM caution:** if we use an ORM, Spring says to flush pending changes inside the test. Otherwise a test can pass even though the same code fails in production.

## Additional Interpretation

_Our own reasoning, not stated in the docs:_

- **Do not wrap the rollback test in `@Transactional`.** The docs say that transactions managed by Spring or by application code usually join the test's transaction. If the test owns the outer transaction, the service's own commit and rollback are not really exercised. Let the service run its own transaction, then read the result afterwards.
- **Use an isolated database that is reset before each test.** This keeps tests independent, protects real data, and fits TQ-5, whether it is an in-memory or fresh temporary SQLite database.

## Source Limitations

- The docs do not contain wallet or ledger examples. The Alice and Bob test is our own design.
- The linked JUnit "current" guide redirected to a newer-version page that could not be opened. The JUnit points above come from the 5.x user guide, so check the exact version we use.
- The Spring pages apply only if our backend uses Spring. The project brief does not name a framework, so the JUnit ideas carry over to any stack but the Spring details may not.

## Related Notes

- [[api-idempotency-keys]]
- [[sqlite-wal-mode]]