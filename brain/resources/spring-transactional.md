---
date: 2026-11-07
tags:
  - financial-ledger
  - spring
  - transactional
  - transactions
  - rollback
source:
  - "https://docs.spring.io/spring-framework/reference/data-access/transaction/declarative/annotations.html"
---
# Spring `@Transactional`

## Summary

A database transaction groups several database changes so that they either all succeed or none of them do. In Spring, you mark a class or method with `@Transactional`, and Spring starts a transaction when that method is called through the Spring-managed bean. By default, Spring rolls the transaction back if the method throws a `RuntimeException` or an `Error`, but not if it throws a checked exception.

> **Labels used below:** _Spring docs_ means documented behavior from the page above. _Ours_ means our own example or project recommendation.

> **Version note:** The linked page is the Spring Framework **7.0.10** reference. Some features it describes, such as the global rollback setting in the third takeaway, were added in Spring 6.2 according to the page. Before relying on a feature, check which Spring Framework version our project will actually use (including the version that our Spring Boot version brings in) and read the documentation for that version.

## Key Takeaways

- **The annotation does nothing by itself (Spring docs).** `@Transactional` is only metadata. Transaction management has to be switched on, for example with `@EnableTransactionManagement` in a `@Configuration` class, and a `TransactionManager` bean must exist (the page's example uses `DataSourceTransactionManager`). The page does not cover Spring Boot's setup.
- **The transaction boundary is the method call through the proxy (Spring docs).** By default (proxy mode), Spring wraps the bean in a proxy, and only calls coming in from outside through that proxy are intercepted. A method calling another method in the same class (self-invocation) does not start a transaction, even if the called method has `@Transactional`.
- **Default rollback rule (Spring docs).** Any `RuntimeException` or `Error` triggers a rollback, and a checked `Exception` does not. This is configurable with `rollbackFor` and `noRollbackFor`. Since Spring 6.2, it can be changed globally with `@EnableTransactionManagement(rollbackOn=ALL_EXCEPTIONS)`, and the page advises doing so unless you rely on EJB-style business exceptions that should commit. **Version-dependent:** the global setting is not available on versions before 6.2.
- **Where to put it (Spring docs).** A class-level annotation sets the default for all methods of that class, and a method-level annotation overrides it. The Spring team recommends annotating methods of concrete classes, not interface methods, because interface annotations can be silently ignored in some modes. The page also warns that the annotation-driven configuration only checks beans in the same application context where it is defined, so putting it in a web context can cover controllers and miss services.
- **Defaults are usually enough to start with (Spring docs).** The default settings are propagation `REQUIRED`, isolation `ISOLATION_DEFAULT`, a read-write transaction, and the underlying system's default timeout.

## Example: Alice transfers ₹500 to Bob

> _This example is ours. The code is a sketch, not tested code._

```java
@Service
public class TransferService {

    @Transactional
    public void transfer(long fromWalletId, long toWalletId, long amountPaise) {
        debit(fromWalletId, amountPaise);        // 1. deduct from Alice
        credit(toWalletId, amountPaise);         // 2. add to Bob
        recordLedgerEntries(fromWalletId, toWalletId, amountPaise); // 3. ledger rows
    }
}
```

These three steps are one unit. If step 1 succeeded and step 2 failed, Alice would lose ₹500 and Bob would gain nothing, and the ledger would not match the balances.

|What happens|Result|
|---|---|
|All three steps finish and the method returns normally|The transaction commits, so all changes are saved together. (The page lists only the rollback rules and does not spell out the commit.)|
|A `RuntimeException` is thrown after debiting Alice and before crediting Bob (Spring docs)|Spring rolls the transaction back, so Alice's debit is undone and nothing is saved.|
|A **checked** exception is thrown at the same point (Spring docs)|The default rule does not roll back, so the earlier changes could end up committed.|

## Relevance to Financial Ledger

- **Database consistency during transfers:** `@Transactional` on the method that performs the whole transfer keeps the balance changes and the ledger entries all-or-nothing. This is how we get the all-or-nothing behavior described in [[sqlite-acid-transactions]] in a Spring application.
- **FR-2 (Idempotent Transfers):** `@Transactional` does not implement idempotency. It makes one attempt all-or-nothing, but it does not notice that the same request arrived twice. A client retry after a successful commit would run the transfer again. We still need the idempotency key logic in [[api-idempotency-keys]].
- **FR-3 (Concurrent Balance Mutability):** `@Transactional` does not solve every concurrency problem. The page lists the isolation level as the database default and says nothing about locking, so how simultaneous transfers behave still depends on the database (for SQLite, see [[sqlite-wal-mode]]). Our code still has to handle errors such as `SQLITE_BUSY`.

## Additional Interpretation

> _Ours, not from the Spring page._

- **Annotate the service method that does the whole transfer.** The page does not say which layer to use, but putting `@Transactional` on one service method means the debit, credit, and ledger writes share one transaction. Annotating only repository calls or the controller would split or widen it. The idempotency-key record can go inside the same transaction, so it is saved only when the transfer is.
- **Do not let business failures be checked exceptions by accident.** For example, if "insufficient funds" is a checked exception, the default rule will not roll back. Make it unchecked, or use `rollbackFor`, or adopt the `ALL_EXCEPTIONS` setting the page recommends if our Spring version supports it. `rollbackFor` is the option that does not depend on the 6.2 feature.
- **Rollback depends on the exception leaving the method.** If code inside the transactional method catches an exception and does not rethrow it, Spring never sees it, so the transaction would commit. The page does not spell this out.

## Source Limitations

- The page is a configuration reference for Spring Framework 7.0.10. It does not explain what a transaction is or how commit works, and it leaves propagation and the detailed rollback rules to other pages that we have not read.
- It is not specific to Spring Boot, and our team has not yet fixed a Spring version. Features added in newer releases, such as the 6.2 rollback setting, may not apply to our project.

## Related Notes

- [[sqlite-acid-transactions]]
- [[api-idempotency-keys]]
- [[sqlite-wal-mode]]