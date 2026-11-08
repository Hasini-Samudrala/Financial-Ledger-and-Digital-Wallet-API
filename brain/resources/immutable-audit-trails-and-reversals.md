---
date: 2026-11-07
tags:
  - financial-ledger
  - audit-trail
  - immutability
  - reversal-transactions
  - event-sourcing
source: "https://martinfowler.com/eaaDev/EventSourcing.html (Martin Fowler, Event Sourcing, 12 December 2005, draft)"
---

# Immutable Audit Trails and Reversal Transactions

## Summary
An audit trail is a record of every change, so we can see how we reached the current state and not just what it is now. Martin Fowler's Event Sourcing article describes one way to get this: store every change as an event, in order. For our wallet, the idea we need is simpler: ledger entries are only ever added, and a mistake is fixed by adding a new reversal transaction, not by editing or deleting the old one.

*Labels used below: **Fowler's article** means what the article says. **Project (FR-4)** means our requirement from the project statement. **Ours** means our own example or implementation choice.*

## Key Takeaways
- **An audit trail answers "how did we get here?" (Fowler's article).** Querying the current state tells you where things stand, but sometimes you also need the history. The article notes that audit matters a great deal in accounting systems.
- **Event Sourcing in one idea (Fowler's article).** Every change to the application's state is captured as an event and stored in the order it happened. The current state can then be rebuilt by replaying the events. The system keeps two things: the current state and the event log. The article calls the event log a purely additive structure.
- **Accounts fit this idea well (Fowler's article).** The article says the accounting entries made for an event can be linked back to that event, which helps with tracking changes and reversals. It even describes an account as an example of Event Sourcing, since its entries are a log of the events that changed its value.
- **We do not need Event Sourcing everywhere (Fowler's article).** The article says the current state in a database can be the official record, with the event log kept only for audit. It also says Event Sourcing is not a natural choice and should be used when you expect a real return.
- **Record changes as differences (Fowler's article).** An event like "add 10 to the balance" is easy to reverse, while "set the balance to 110" is not, because the old value is lost. In our ledger, a debit or credit of a fixed amount is the first kind (ours).

## Example: Reversing Alice's transfer
*This example is ours. It uses the convention from [[double-entry-accounting]], where a wallet balance is a liability: a debit lowers a balance and a credit raises it.*

Alice sent Bob ₹500 in transaction T1. It later has to be cancelled. Instead of editing or deleting T1, we add transaction T2 that reverses it.

| Transaction            | Account        | Debit | Credit |
| ---------------------- | -------------- | ----- | ------ |
| T1 (original transfer) | Alice's wallet | ₹500  |        |
| T1 (original transfer) | Bob's wallet   |       | ₹500   |
| T2 (reversal of T1)    | Bob's wallet   | ₹500  |        |
| T2 (reversal of T1)    | Alice's wallet |       | ₹500   |

- The four rows stay in the ledger forever. T2 is linked to T1, so anyone reading the ledger can see that T1 was reversed.
- Alice's and Bob's balances end up where they started, because T2 undoes T1's effect.
- Each transaction still balances (₹500 debit and ₹500 credit), so reconciliation still works.

## Relevance to Financial Ledger
- **FR-4 (Immutable Audit Trail):** The project statement says ledger records cannot be updated or deleted once written, and that corrections or cancellations must be recorded as an explicit reversal transaction. Fowler's article does not specify this. It is our own requirement, and the article only supports the general idea of keeping a history.
- **Why not update or delete entries:** If an entry could be edited, we could no longer tell what was recorded originally, and reconciliation (FR-5) could be made to match by quietly changing the evidence. Keeping the original and the correction shows what happened, when, and that it was later reversed.
- **Why both records are useful:** The original shows the money moved. The reversal shows it was cancelled. Together they give an honest history that a person can follow, such as support staff or an auditor.
- **How this relates to Event Sourcing:** The ledger works like the article's event log, since entries are only added. But we only need the append-only part. The article's extras, such as rebuilding all state or replaying events, are not required by FR-4.

## Additional Interpretation
*Ours, not from the article.*
- **Append-only ledger, ordinary tables elsewhere.** Only the ledger table needs the append-only rule. Other tables, such as wallets and idempotency keys, can stay ordinary. One way to enforce the rule is for the code to only insert into the ledger, and to add database triggers that block `UPDATE` and `DELETE` on it.
- **Reversals need rules.** For example, a transaction should only be reversed once. We also need to decide what happens if Bob has already spent the ₹500, since the reversal would take it from a wallet that no longer has it.

## Source Limitations
The article is a 2005 draft about application state in general, and it does not discuss financial reversal transactions. When it says "reverse", it means an event undoing its own effect on the application state, which is different from recording a new correcting transaction in a ledger. It also does not say how to enforce that records cannot be changed.

## Related Notes
- [[double-entry-accounting]]
- [[sqlite-acid-transactions]]