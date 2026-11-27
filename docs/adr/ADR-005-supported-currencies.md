# ADR-005: Supported currencies and minor-unit scales

- **Status:** Accepted
- **Date:** 2026-11-25
- **Deciders:** Team 404 (all members approved 2026-11-25)
- **SRS reference:** SRS Appendix C TBD-01, FR-1.2 (one wallet per supported
  currency), §6.1 (minor units)
- **Research notes:** [[monetary-precision]]

---

## Context

The SRS does not specify which currencies are supported or their minor-unit
scales (TBD-01). Every part of the system that validates amounts or creates
wallets depends on this list. This was a blocking decision before implementation.

## Options evaluated

| Currency | ISO 4217 code | Minor units | Scale |
|---|---|---|---| 
| Indian Rupee | INR | Paise | 2 (100 paise = ₹1) |
| US Dollar | USD | Cents | 2 (100 cents = \$1) |
| Japanese Yen | JPY | Yen (no subdivisions) | 0 |
| Kuwaiti Dinar | KWD | Fils | 3 (1000 fils = 1 KWD) |

JPY and KWD were considered but add implementation complexity (non-standard
scales) beyond the scope of this demonstration system.

## Decision

**Support INR and USD only. Both have scale 2.**

| Currency | ISO 4217 | Minor unit name | Scale | Example |
|---|---|---|---|---|
| Indian Rupee | `INR` | Paise | 2 | ₹10.00 → `amountMinor: 1000` |
| US Dollar | `USD` | Cents | 2 | \$10.00 → `amountMinor: 1000` |

### Representation

All amounts are stored and transmitted as **64-bit integers in minor units**
(SRS §6.1). No floating-point arithmetic is used anywhere in the system.

```
₹10.00  → stored/transmitted as integer 1000  (paise)
$10.00  → stored/transmitted as integer 1000  (cents)
```

### Currency storage

Currencies are stored as their ISO 4217 code string (`"INR"`, `"USD"`) in
the `Wallet.currency_code` and `LedgerEntry.currency_code` columns. There is
no separate currency table.

### Configuration

Supported currencies are declared in `application.properties`:

```properties
app.supported-currencies=INR,USD
app.currency.INR.scale=2
app.currency.USD.scale=2
```

A `SupportedCurrencies` configuration bean reads these properties at startup
and exposes `isSupported(String code)` and `getScale(String code)` methods.

### Request validation

Any request supplying a `currencyCode` not in `{INR, USD}` receives:

```
400 Bad Request
{"error":{"code":"UNSUPPORTED_CURRENCY","message":"Currency code 'XYZ' is not supported."}}
```

The `amountMinor` field must be a **positive integer** (≥ 1). The system
does not round or convert; `amountMinor` is validated as-is.

Cross-currency transfers are rejected:

```
400 Bad Request
{"error":{"code":"CURRENCY_MISMATCH","message":"Source and destination wallets must use the same currency."}}
```

### System funding accounts — automatic initialisation

Each supported currency requires one `SYSTEM_FUNDING` ledger account that acts
as the debit side of admin funding operations (FR-1.4). These accounts are
**created automatically at application startup** via an idempotent initialisation
routine:

```sql
INSERT OR IGNORE INTO ledger_accounts (account_id, account_type, currency_code)
VALUES ('SYSTEM_FUNDING_INR', 'SYSTEM_FUNDING', 'INR'),
       ('SYSTEM_FUNDING_USD', 'SYSTEM_FUNDING', 'USD');
```

The `INSERT OR IGNORE` ensures the statement is safe to run on every start,
including when accounts already exist.

> Account IDs `SYSTEM_FUNDING_INR` and `SYSTEM_FUNDING_USD` are fixed
> well-known identifiers. They must not be used as wallet IDs.

## Consequences

| Positive | Negative |
|---|---|
| Small, well-understood currency set; both use scale 2 (uniform implementation) | Adding a new currency (e.g. JPY at scale 0) requires a code change and new config |
| Automatic system account init simplifies deployment | — |
| Property-driven config makes the list easy to extend | — |

## Resolves

- **TBD-01** (SRS Appendix C): Currency list and scales — resolved by this ADR.

## Sign-off

All team members approved 2026-11-25. Status changed from APPROVAL-REQUIRED to Accepted.
