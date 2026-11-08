---
date: 2026-11-07
tags:
  - financial-ledger
  - double-entry
  - accounting
  - debits-and-credits
source: "https://www.accountingcoach.com/debits-and-credits/explanation"
---
# Double-Entry Accounting

## Summary

Double-entry accounting means every transaction is written down in at least two accounts. One account gets a debit (an entry on the left side) and another gets a credit (an entry on the right side). The total of the debits must always equal the total of the credits, which keeps the records accurate.

## Key Takeaways

- **Every transaction touches at least two accounts.** For example, when a company borrows money from a bank, both its Cash account and its Loan account change. A transaction can involve more than two accounts, but never fewer.
- **Debit means left, credit means right.** They are positions on an account, not "plus" and "minus". Whether a debit increases or decreases an account depends on the account type. Debits increase assets and credits decrease them. Credits increase liabilities (money owed) and debits decrease them.
- **Debits must equal credits.** In every transaction, the debit total must match the credit total. The source presents this as the rule that keeps the records accurate.
- **A balance comes from its entries.** An account's balance is what is left after adding up its debits and credits. In the source's example, borrowing 5,000 and repaying 2,000 leaves a balance of 3,000.
- **A bank is a useful model for a wallet.** When you deposit cash, the bank debits its own Cash and credits an account that shows what it owes you. That is why a bank says it "credits your account" when your balance goes up.

## Example: Alice transfers ₹500 to Bob

_This example is ours, not from the source. It applies the source's bank logic, where each wallet balance is money the service owes to a user (a liability)._

|Account|Debit|Credit|
|---|---|---|
|Alice's wallet|₹500||
|Bob's wallet||₹500|
|**Total**|**₹500**|**₹500**|

- Alice's wallet is debited, so her balance goes down by ₹500. A debit decreases a liability.
- Bob's wallet is credited, so his balance goes up by ₹500. A credit increases a liability.
- The totals match, so no money was created or lost.

## Relevance to Financial Ledger

- **FR-1 (Wallet Initialization):** Each wallet works like an account, and its balance should come from its ledger entries rather than being a number we overwrite. This matches the source's idea of a balance built from entries.
- **FR-2 (Idempotent Transfers):** One transfer is one balanced set of entries, like the Alice and Bob table. The source does not discuss retries or idempotency, so preventing a repeated request from posting the entries twice is something our API has to handle.
- **FR-5 (Ledger Reconciliation):** The reconciliation endpoint can use the source's rule directly: total debits must equal total credits. The second check, wallet balances matching the ledger, follows from the balance-comes-from-entries idea.

## Additional Interpretation

_Ours, not from the source._

- **Sign convention:** Treating wallets as liabilities means credit raises a balance and debit lowers it. We should pick one convention and document it, because it is easy to confuse with how a customer thinks of their own cash.
- **Duplicates still balance:** If a transfer were posted twice, debits would still equal credits. So the balance check alone cannot catch a double charge, which is why FR-2 and the balance-versus-ledger check in FR-5 both matter.
- **Starting balances:** A top-up or initial balance also needs an opposite entry somewhere. The source does not cover this, so it is a design choice for us.

## Source Limitations

The source is an introduction to business accounting, not to software. It says nothing about APIs, idempotency, concurrency, or how to store amounts, so those requirements come from our project statement only.

## Related Notes

- [[sqlite-acid-transactions]]
- [[sqlite-wal-mode]]