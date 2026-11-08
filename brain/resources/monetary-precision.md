---
date: 2026-11-07
tags:
  - financial-ledger
  - money
  - bigdecimal
  - minor-units
  - floating-point
  - sqlite
source:
  - "https://docs.oracle.com/javase/tutorial/java/nutsandbolts/datatypes.html"
  - "https://docs.oracle.com/javase/8/docs/api/java/math/BigDecimal.html"
  - "https://www2.sqlite.org/datatype3.html"
---
# Monetary Precision: Floating Point vs Decimal / Minor Units

## Summary

Computers store `float` and `double` numbers in binary, so many everyday decimal amounts such as 0.10 cannot be stored exactly. For money, there are two safe approaches: Java's `BigDecimal`, which can represent decimal values exactly when it is created correctly (for example from a `String`), or whole numbers of the smallest currency unit (for example, ₹10.25 stored as 1025 paise). Either way, we still have to decide how rounding works.

> **Labels used below:** _Official docs_ means it comes from the Java or SQLite documentation. _Ours_ means our own example or project recommendation.

## Key Takeaways

- **Do not use `float` or `double` for money (official docs).** The Java Tutorials say these types should never be used for precise values such as currency, and point to `BigDecimal` instead. They are binary floating-point types, and the `BigDecimal` docs show the problem: `new BigDecimal(0.1)` is not exactly 0.1, but about 0.1000000000000000055511.
- **`BigDecimal` can represent decimal values exactly, if built correctly (official docs).** A `BigDecimal` is immutable (it never changes after creation) and is made of an integer _unscaled value_ and a _scale_. Its value is the unscaled value times 10 to the power of minus the scale, so 10.25 is unscaled value 1025 with scale 2. The docs recommend building it from a `String` (`new BigDecimal("0.1")`), not from a `double`, because the `double` already carries the binary error.
- **`BigDecimal` still needs careful scale and rounding (official docs).** Multiplying two values with scale 2 gives scale 4. Dividing can throw an `ArithmeticException` when the exact answer never ends (1 divided by 3) unless we choose a scale and a rounding mode. `equals` treats 2.0 and 2.00 as different, while `compareTo` treats them as equal.
- **Integer minor units avoid decimals entirely (ours).** Store ₹10.25 as 1025 paise in a `long`. Adding and subtracting whole numbers is exact. The trade-offs are converting to and from rupees at the edges of the system, and still needing a rounding rule wherever a result is not a whole number of paise (a percentage fee, a division).
- **SQLite has no exact decimal type (official docs).** A column declared `DECIMAL(10,5)` gets NUMERIC affinity, which means SQLite stores the value as an INTEGER or a REAL (an 8-byte floating-point number). INTEGER values are signed and take up to 8 bytes. A decimal in text form can be converted to REAL and lose exactness.

## `BigDecimal` vs Integer Minor Units

|Aspect|`BigDecimal`|Integer minor units (`long`)|
|---|---|---|
|Exact for amounts like 10.25 (docs)|Yes, if built from a `String` or from an unscaled value and scale|Yes|
|Storage in SQLite (docs, applied by us)|A `DECIMAL` column would turn it into INTEGER or REAL, so store it as TEXT or as its unscaled integer|Natural: stored as INTEGER|
|Rounding (docs)|Must choose scale and rounding mode wherever digits are discarded|Must define rounding wherever a result is not a whole number of paise|
|Readability (ours)|Method calls like `add`, and the scale travels with the value|Plain arithmetic, but easy to forget which unit a number is in|

## Example: Why accuracy matters in a wallet

> _This example is ours. We checked the `double` results by running the same arithmetic in Python, which uses the same IEEE 754 doubles as Java. The `BigDecimal` result follows from the docs._

A user adds ₹0.10 to their wallet ten times. With `double`, the balance ends up as 0.9999999999999999, not 1.0, so a check like `balance == 1.00` is false and a "pay exactly ₹1.00" transfer could be refused. Likewise, `1.00 - 7 * 0.10` gives 0.29999999999999993 instead of 0.30. With integer paise, ten additions of 10 give exactly 100. With `BigDecimal` built from strings, 0.1 + 0.2 is exactly 0.3.

## Relevance to Financial Ledger

- **Why it matters for transfers:** A transfer must take exactly ₹X from one wallet and give exactly ₹X to another. If amounts carry tiny binary errors, the debit and credit can drift apart, or a balance check can fail by a hair.
- **FR-2 (Idempotent Transfers):** The project statement (TQ-6) requires integer minor units or exact decimals. An exact, single form for each amount also helps if we compare or hash request payloads for retries: `BigDecimal` treats 2.0 and 2.00 as different in `equals`, while 200 paise is just 200.
- **FR-5 (Ledger Reconciliation):** Reconciliation compares credit and debit totals and wallet balances against ledger sums. With exact amounts, the check can demand exact equality. With rounding errors, totals could differ by tiny amounts, producing false mismatches or hiding real ones.

## Additional Interpretation

> _Ours, not from the docs._

- **Recommendation: store money as integer minor units (`long`) in the database and the core ledger logic.** For rupees that means paise. It matches SQLite's INTEGER type and keeps totals simple to add and compare. `BigDecimal` can help at the API edge to parse and format amounts, but should not be saved in a `DECIMAL` column.
- **Define minor units for each currency.** FR-1 uses a separate wallet per currency, and the number of minor-unit digits can differ between currencies. Keep the currency with every amount and define its minor-unit size in one place.
- **Define rounding in one place.** Reject amounts that are not a whole number of minor units, and write one rule for any future fees or interest. Neither `BigDecimal` nor integer minor units handles scale and rounding for us.

## Source Limitations

The Java and SQLite pages describe how the types behave but do not say which approach a ledger should use, so the recommendation above is ours.

## Related Notes

- [[double-entry-accounting]]
- [[sqlite-acid-transactions]]