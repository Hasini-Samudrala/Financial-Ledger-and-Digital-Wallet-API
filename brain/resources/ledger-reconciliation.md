---
date: 2026-11-07
tags:
  - ledger-reconciliation
  - double-entry
  - debits-and-credits
  - fr-5
  - digital-wallet
source:
  - "https://www.accountingcoach.com/debits-and-credits/explanation"
---
# Ledger Reconciliation

## Summary

In double-entry accounting, every transaction is written down as at least one debit and at least one credit, and the totals must be equal. Ledger reconciliation means re-checking the stored records afterwards: does everything still balance, and do the stored balances agree with the entries that should explain them? For our wallet API, this is how we prove that no money has appeared, vanished or been double-counted.

## Key Takeaways

- **Every transaction touches at least two accounts.** At least one account is debited (left side) and at least one is credited (right side). _(AccountingCoach)_
- **Debits must equal credits.** The page states that the total debited in a transaction must equal the total credited, and that each journal entry must balance. It describes the double-entry system as a way to provide accuracy in the records. _(AccountingCoach)_
- **To decrease an account, do the opposite of what increases it.** In the page's bank example, a customer's deposit balance is a liability of the bank. A credit increases it and a debit decreases it. _(AccountingCoach)_
- **A balance is a result of the entries.** The page's T-account example shows entries on each side and a balance worked out from them: $5,000 received, $2,000 paid, $3,000 balance. _(AccountingCoach)_

## Relevance to Financial Ledger

AccountingCoach explains only the debit/credit basics. The reconciliation endpoint and the comparison with stored wallet balances come from **our project brief (FR-5)**, not from AccountingCoach.

### Example: Alice starts with ₹1,000 and sends ₹300 to Bob

_This modelling is ours, based on the page's bank example. It assumes Bob starts at ₹0 and that opening money enters through a funding account, which the brief does not specify._

|Entry|Account|Debit|Credit|
|---|---|---|---|
|1. Opening funds|System funding account|₹1,000||
||Alice's wallet||₹1,000|
|2. Transfer|Alice's wallet|₹300||
||Bob's wallet||₹300|

- **Totals:** debits ₹1,300 and credits ₹1,300, so the ledger balances.
- **Calculated balances:** a wallet balance is its credits minus its debits.
    - Alice: ₹1,000 − ₹300 = **₹700**
    - Bob: ₹300 − ₹0 = **₹300**
- In the real system these amounts would be stored as integer minor units (TQ-6).

### The two checks our admin endpoint must perform (FR-5)

1. **Total debits equal total credits.** Add up every debit and every credit in the ledger and confirm they match. Checking each transaction on its own would also help us find the culprit quickly (our suggestion).
2. **Each wallet's stored balance equals its balance calculated from ledger entries.** For every wallet, recompute credits minus debits and compare the result with the stored balance.

### What a mismatch means

- **Debits and credits differ:** an entry is missing, duplicated or recorded with the wrong amount. For example, a transfer wrote only one side.
- **A stored balance differs from its ledger sum:** the balance was changed without matching entries, or entries were written without updating the balance. Failed rollbacks and concurrency bugs (see the related notes) are likely causes.

Either way, the records can no longer be trusted, so the cause must be investigated. Under FR-4, any correction is made with an explicit reversal transaction. We should never edit or delete entries to make the numbers match.

### Reconciliation is not "did the transfer succeed?"

A successful API response says one request finished. Reconciliation scans the whole ledger and checks that the history still adds up, including after failures, retries and concurrent requests.

## Additional Interpretation

_Our own reasoning, not stated in AccountingCoach:_

- Reconciliation cannot catch every mistake. If a wrong amount is recorded consistently on both sides, the ledger still balances. Input validation and the transfer tests are still needed.
- The endpoint should report which wallet or transaction failed a check, not just a pass or fail result, so the team can investigate quickly.

## Source Limitations

- AccountingCoach does not discuss ledger reconciliation of the kind our project needs. It covers debit and credit rules and journal entries, so our definition of FR-5 comes from the project brief.
- Wallet balances as credit-balance accounts is our adaptation of the page's bank example, not something the page says about wallets.

## Related Notes

- [[race-conditions]]
- [[integration-testing-and-rollback]]