# Database and Ledger Design

-   **Project:** Financial Ledger & Digital Wallet API
-   **Team:** 404
-   **Document:** `database-ledger-design.md`
-   **Source baseline:** SRS v1.3, especially Sections 2.4--2.5,
    4.1--4.5, 6.1 and Appendices B--C
-   **Status:** Proposed technical design for team review
-   **Scope:** SQLite schema and ledger design; application
    implementation is out of scope

------------------------------------------------------------------------

## 1. Purpose

This document describes how the project's database and financial ledger
are proposed to work.

It covers the eight entities listed in Appendix B:

1.  User
2.  Wallet
3.  LedgerAccount
4.  LedgerTransaction
5.  Transfer
6.  IdempotencyRecord
7.  LedgerEntry
8.  IntegrityCheckpoint

The design explains their relationships, constraints, double-entry
accounting, reversals, immutable history, integrity checking and
reconciliation.

**Important:** The SRS is the requirements baseline. Where it does not
specify a database detail, this document labels the choice as a
**Proposed design decision**. Those choices need team review before
implementation.

## 2. Requirements from SRS v1.3

The database design must support the following requirements.

  -----------------------------------------------------------------------
  Requirement                         What the database design must
                                      support
  ----------------------------------- -----------------------------------
  FR-1.2                              One wallet per user per supported
                                      currency

  FR-1.3                              New wallets start at zero; balances
                                      change only through ledger activity

  FR-1.4                              Admin funding records a debit to a
                                      system funding account and an equal
                                      wallet credit

  FR-2.1--2.3                         Positive, same-currency transfers
                                      with equal source debit and
                                      destination credit

  FR-2.4                              Transfer, entries, idempotency
                                      result, balances and checkpoint
                                      commit atomically

  FR-2.5--2.7                         Idempotency keys are scoped per
                                      user; same key and same data
                                      replays; changed data conflicts;
                                      failed attempts are not cached as
                                      successes

  FR-3.1--3.5                         Concurrent writes do not cause
                                      overspending, lost updates or
                                      partial writes

  FR-4.1                              Committed ledger entries cannot be
                                      updated or deleted

  FR-4.2                              Each entry stores sequence,
                                      transaction, account, currency,
                                      debit/credit, previous hash, own
                                      hash and UTC timestamp

  FR-4.3--4.4                         Hash-chain and external-secret
                                      integrity checks detect tampering
                                      and report the earliest mismatch

  FR-4.5--4.6                         Reversals are new compensating
                                      transactions; original entries stay
                                      unchanged; at most one full
                                      reversal

  FR-4.7                              Ledger history is returned
                                      chronologically, in pages of up to
                                      100 entries

  FR-5.1--5.4                         Reconciliation checks currency
                                      totals and wallet balances using
                                      one consistent snapshot; it changes
                                      nothing

  Section 6.1                         Money uses 64-bit integer minor
                                      units; SQLite uses WAL and
                                      transactions for financial writes
  -----------------------------------------------------------------------

The SRS says the system is for demonstration only and does not hold real
funds.

## 3. Database overview

### 3.1 Technology

The SRS specifies Java with Spring Boot, SQLite through JDBC, and SQLite
WAL mode.

  -----------------------------------------------------------------------
  Item                                Design
  ----------------------------------- -----------------------------------
  Database                            SQLite

  Database access                     JDBC

  Journal mode                        WAL

  Foreign keys                        Enabled on every connection

  Financial writes                    Database transactions

  Monetary values                     64-bit integer minor units

  Identifiers                         UUIDs, stored as TEXT (proposed
                                      representation)

  Timestamps                          UTC timestamps, stored as TEXT
                                      (proposed representation)

  Hashes and MACs                     BLOB

  SQL                                 Parameterised statements
  -----------------------------------------------------------------------

SQLite's single-writer behaviour must be respected. The write lock is
taken before the balance check, as required by FR-3.2.

### 3.2 Money representation

Money must never be stored as `FLOAT` or `DOUBLE`.

The SRS requires 64-bit integer minor units, with precision depending on
the currency.

Examples:

  Amount      Stored value for a 2-decimal currency
  --------- ---------------------------------------
  ₹100.00                                     10000
  ₹25.50                                       2550
  ₹0.01                                           1

The currency's allowed minor-unit precision must be known before
validating an amount. Excess precision is rejected, not rounded.

**Open decision:** Supported currencies and their minor-unit scales are
TBD-01.

## 4. Entity design

The following sections describe the eight Appendix B entities. Extra
columns are marked as proposed where Appendix B does not specify them.

### 4.1 User

**Purpose:** Represents a registered user who owns wallets.

  Column           Type   Rules
  ---------------- ------ -------------------
  `api_key_id`     TEXT   NOT NULL, UNIQUE; public part of the key
  `api_key_hash`   BLOB   NOT NULL
  `api_key_salt`   BLOB   NOT NULL

Each API key has the form `<api_key_id>.<secret>`. The server uses api_key_id to find the row, then checks the secret against api_key_hash using api_key_salt. The format is proposed and needs team approval.

The API key is shown to the user only once. The database stores its
salted hash, not the original key. The admin key comes from
configuration and is not stored in this table.

### 4.2 Wallet

**Purpose:** Represents a user's wallet for one currency and stores its
current balance.

  ------------------------------------------------------------------------
  Column                   Type                    Rules
  ------------------------ ----------------------- -----------------------
  `wallet_id`              TEXT                    Primary key; UUID

  `user_id`                TEXT                    Foreign key to User

  `currency_code`          TEXT                    NOT NULL

  `stored_balance_minor`   INTEGER                 NOT NULL; defaults to
                                                   zero; cannot be
                                                   negative
  ------------------------------------------------------------------------

A user can have one wallet per supported currency. The pair
`(user_id, currency_code)` must be unique.

A wallet's stored balance is a convenient current value. Its
authoritative balance is derived from its ledger account:

`ledger balance = total credits - total debits`

The stored balance must be updated atomically with ledger entries. It
must never be changed as an independent financial operation.

### 4.3 LedgerAccount

**Purpose:** Represents an accounting account affected by ledger
entries.

A wallet and a ledger account are related, but they are not the same
thing:

-   A **Wallet** represents the user's wallet and its stored balance.
-   A **LedgerAccount** represents that wallet in the accounting
    records.
-   A system funding account represents the source of demo funds and is
    not a user's wallet.

  -----------------------------------------------------------------------
  Column                  Type                    Rules
  ----------------------- ----------------------- -----------------------
  `account_id`            TEXT                    Primary key; UUID

  `account_type`          TEXT                    NOT NULL; proposed
                                                  values `WALLET`,
                                                  `SYSTEM_FUNDING`

  `wallet_id`             TEXT                    Foreign key to Wallet;
                                                  NULL for system account

  `currency_code`         TEXT                    NOT NULL
  -----------------------------------------------------------------------

**Proposed rules:**

-   A `WALLET` account must reference one wallet.
-   A `SYSTEM_FUNDING` account must not reference a wallet.
-   A wallet can have at most one LedgerAccount.
-   The application creates the wallet and its LedgerAccount atomically.

A normal foreign key can ensure that an account's referenced wallet
exists. It cannot, by itself, ensure every wallet has an account; the
creation workflow must enforce that.

### 4.4 LedgerTransaction

**Purpose:** Groups all ledger entries belonging to one financial
operation.

  -------------------------------------------------------------------------
  Column                    Type                    Rules
  ------------------------- ----------------------- -----------------------
  `ledger_transaction_id`   TEXT                    Primary key; UUID

  `type`                    TEXT                    NOT NULL; proposed
                                                    values `FUNDING`,
                                                    `TRANSFER`, `REVERSAL`

  `reference_id`            TEXT                    NOT NULL

  `created_at`              TEXT                    NOT NULL; UTC
  -------------------------------------------------------------------------

A transfer of ₹100 from Alice to Bob is one LedgerTransaction containing
at least two LedgerEntries: one debit and one equal credit.

**Included column:** `currency_code TEXT NOT NULL` is part of the schema 
(Section 6.4). It makes it easier to ensure all entries in that transaction 
use the same currency.

`reference_id` identifies the business operation that caused the
accounting transaction. For a transfer or reversal, it can contain the
corresponding `transfer_id`. For funding, it can contain a unique
funding-operation identifier.

**Important limitation:** Because `reference_id` can identify different
kinds of operations, it is a polymorphic reference. A normal foreign key
cannot validate both kinds of target. The application must validate it.

### 4.5 Transfer

**Purpose:** Stores the business details of a transfer between two
wallets.

  ---------------------------------------------------------------------------
  Column                      Type                    Rules
  --------------------------- ----------------------- -----------------------
  `transfer_id`               TEXT                    Primary key; UUID

  `source_wallet_id`          TEXT                    Foreign key to Wallet

  `destination_wallet_id`     TEXT                    Foreign key to Wallet

  `amount_minor`              INTEGER                 Positive 64-bit integer

  `currency_code`             TEXT                    NOT NULL

  `status`                    TEXT                    Proposed values
                                                      `COMPLETED`, `REVERSED`

  `reversal_of_transfer_id`   TEXT                    Nullable self-reference
                                                      to original Transfer

  `created_at`                TEXT                    NOT NULL; UTC
  ---------------------------------------------------------------------------

Appendix B describes the wallets involved without specifying separate
source and destination columns. **Proposed design:** use
`source_wallet_id` and `destination_wallet_id` so that each wallet can
be checked with a foreign key.

Rules from the SRS:

-   Source and destination must be different.
-   Both wallets must use the same currency.
-   Amount must be positive and valid for that currency.
-   Source must have sufficient funds.
-   A reversal is full, not partial.
-   A transfer can be reversed at most once.
-   A reversal record has status `COMPLETED` and references the original
    transfer.
-   A reversal cannot itself be reversed.
-   The original transfer may change status from `COMPLETED` to
    `REVERSED`; its ledger entries remain unchanged.

### 4.6 IdempotencyRecord

**Purpose:** Ensures that retrying the same successful request does not
create another transfer.

  Column                  Type   Rules
  ----------------------- ------ ----------------------------------
  `user_id`               TEXT   Foreign key to User
  `idempotency_key`       TEXT   NOT NULL; 1--64 ASCII characters
  `request_fingerprint`   BLOB   NOT NULL
  `transfer_id`           TEXT   Proposed foreign key to Transfer
  `created_at`            TEXT   Proposed UTC timestamp

The primary key is `(user_id, idempotency_key)`, so a key is unique for
each user.

The SRS requires the request fingerprint to cover the user, wallets,
amount and currency.

**Proposed addition:** Store `transfer_id` so the original successful
result can be found and replayed. If the API must replay an exact
response rather than reconstruct it from the transfer, the team must
decide whether response data also needs to be stored.

Only successful committed transfers are cached as successes. Failed
validation, insufficient-funds and rolled-back attempts are not stored
as successful idempotency records.

The same amount is not automatically a duplicate: a new intentional
request uses a new idempotency key. A retry of the same request reuses
its original key.

### 4.7 LedgerEntry

**Purpose:** Stores the actual debit and credit records. This is the
permanent financial history.

  Column             Type      Rules
  ------------------ --------- ----------------------------------
  `sequence_no`      INTEGER   Unique, increasing sequence
  `transaction_id`   TEXT      Foreign key to LedgerTransaction
  `account_id`       TEXT      Foreign key to LedgerAccount
  `currency_code`    TEXT      NOT NULL
  `debit_minor`      INTEGER   Non-negative integer
  `credit_minor`     INTEGER   Non-negative integer
  `previous_hash`    BLOB      NOT NULL
  `entry_hash`       BLOB      NOT NULL
  `created_at`       TEXT      NOT NULL; UTC
  `entry_id`         TEXT      Proposed primary key; UUID

Appendix B lists the sequence, transaction, account, currency amounts
and hashes. `entry_id` is an additional proposed identifier. A separate
`currency_code` column follows FR-4.2.

Exactly one of debit and credit must be positive. The other must be
zero.

### 4.8 IntegrityCheckpoint

**Purpose:** Stores the authenticated head of the ledger hash chain.

  Column                  Type      Rules
  ----------------------- --------- --------------------------------
  `chain_head_hash`       BLOB      NOT NULL
  `last_entry_sequence`   INTEGER   NOT NULL
  `checkpoint_mac`        BLOB      NOT NULL
  `checkpoint_id`         INTEGER   Proposed singleton primary key
  `updated_at`            TEXT      Proposed UTC timestamp

Appendix B specifies the chain head, last sequence and checkpoint MAC.
The singleton ID and timestamp are proposed additions.

The checkpoint MAC is calculated using an external secret. That secret
must not be stored in the SQLite database or committed to the
repository.

## 5. Relationships between entities

  --------------------------------------------------------------------------------
  Parent              Child                 Relationship         Meaning
  ------------------- --------------------- -------------------- -----------------
  User                Wallet                One-to-many          A user can own
                                                                 multiple currency
                                                                 wallets

  Wallet              LedgerAccount         One-to-one           A wallet has 
                                                                 exactly one 
                                                                 accounting account

  Wallet              Transfer              One-to-many as       A wallet can send
                                            source               many transfers

  Wallet              Transfer              One-to-many as       A wallet can
                                            destination          receive many
                                                                 transfers

  User                IdempotencyRecord     One-to-many          A user can have
                                                                 many idempotency
                                                                 keys

  Transfer            IdempotencyRecord     One-to-zero-or-one   A successful
                                            in this proposal     transfer is
                                                                 associated with
                                                                 its request
                                                                 record

  Transfer            Transfer              Self-reference       A reversal points
                                                                 to its original
                                                                 transfer

  LedgerTransaction   LedgerEntry           One-to-many          One financial
                                                                 operation groups
                                                                 its entries

  LedgerAccount       LedgerEntry           One-to-many          An account has a
                                                                 history of
                                                                 entries

  Transfer            LedgerTransaction     Conceptual reference The transaction's
                                                                 `reference_id`
                                                                 identifies the
                                                                 transfer

  LedgerEntry         IntegrityCheckpoint   Chain-level          The checkpoint
                                            relationship         authenticates the
                                                                 chain head, not
                                                                 one individual
                                                                 entry
  --------------------------------------------------------------------------------

The one-to-zero-or-one Transfer--IdempotencyRecord relationship is a
proposed simplification for the successful transfer flow. The SRS
requires successful result replay but does not specify this foreign key.

## 6. Proposed SQLite schema

The SQL below is a proposed starting schema. It includes the eight
Appendix B tables and core relational constraints. It does not replace
application-level validation, transaction-level accounting checks or
cryptographic verification.

Enable foreign keys on **every connection**:

```sql
PRAGMA foreign_keys = ON;
```

Set WAL mode **once**, when the database is created:

```sql
PRAGMA journal_mode = WAL;
```

### 6.1 User

``` sql
CREATE TABLE users (
    user_id TEXT PRIMARY KEY,
    api_key_id TEXT NOT NULL UNIQUE,
    api_key_hash BLOB NOT NULL,
    api_key_salt BLOB NOT NULL
);
```

### 6.2 Wallet

``` sql
CREATE TABLE wallets (
    wallet_id TEXT PRIMARY KEY,
    user_id TEXT NOT NULL,
    currency_code TEXT NOT NULL,
    stored_balance_minor INTEGER NOT NULL DEFAULT 0
        CHECK (
            typeof(stored_balance_minor) = 'integer'
            AND stored_balance_minor >= 0
        ),

    FOREIGN KEY (user_id)
        REFERENCES users(user_id)
        ON DELETE RESTRICT,

    UNIQUE (user_id, currency_code),
    UNIQUE (wallet_id, currency_code)
);
```

### 6.3 LedgerAccount

``` sql
CREATE TABLE ledger_accounts (
    account_id TEXT PRIMARY KEY,

    account_type TEXT NOT NULL
        CHECK (account_type IN ('WALLET', 'SYSTEM_FUNDING')),

    wallet_id TEXT,
    currency_code TEXT NOT NULL,

    CHECK (
        (account_type = 'WALLET' AND wallet_id IS NOT NULL)
        OR
        (account_type = 'SYSTEM_FUNDING' AND wallet_id IS NULL)
    ),

    FOREIGN KEY (wallet_id, currency_code)
        REFERENCES wallets(wallet_id, currency_code)
        ON DELETE RESTRICT,

    UNIQUE (wallet_id),
    UNIQUE (account_id, currency_code)
);
```

SQLite permits multiple NULL values in a UNIQUE column. This allows more
than one system account with a NULL wallet ID. If the project requires
exactly one system funding account per currency, add a partial unique
index:

``` sql
CREATE UNIQUE INDEX uq_system_funding_currency
ON ledger_accounts(currency_code)
WHERE account_type = 'SYSTEM_FUNDING';
```

### 6.4 LedgerTransaction

``` sql
CREATE TABLE ledger_transactions (
    ledger_transaction_id TEXT PRIMARY KEY,

    type TEXT NOT NULL
        CHECK (type IN ('FUNDING', 'TRANSFER', 'REVERSAL')),

    reference_id TEXT NOT NULL,
    currency_code TEXT NOT NULL,
    created_at TEXT NOT NULL,

    UNIQUE (type, reference_id),
    UNIQUE (ledger_transaction_id, currency_code)
);
```

`currency_code` is a proposed addition for currency consistency.
`UNIQUE(type, reference_id)` prevents the same operation reference from
being used twice for the same transaction type.

### 6.5 Transfer

``` sql
CREATE TABLE transfers (
    transfer_id TEXT PRIMARY KEY,

    source_wallet_id TEXT NOT NULL,
    destination_wallet_id TEXT NOT NULL,
    currency_code TEXT NOT NULL,

    amount_minor INTEGER NOT NULL
        CHECK (
            typeof(amount_minor) = 'integer'
            AND amount_minor > 0
        ),

    status TEXT NOT NULL
        CHECK (status IN ('COMPLETED', 'REVERSED')),

    reversal_of_transfer_id TEXT,
    created_at TEXT NOT NULL,

    CHECK (source_wallet_id <> destination_wallet_id),

    CHECK (
        reversal_of_transfer_id IS NULL
        OR reversal_of_transfer_id <> transfer_id
    ),

    FOREIGN KEY (source_wallet_id, currency_code)
        REFERENCES wallets(wallet_id, currency_code),

    FOREIGN KEY (destination_wallet_id, currency_code)
        REFERENCES wallets(wallet_id, currency_code),

    FOREIGN KEY (reversal_of_transfer_id)
        REFERENCES transfers(transfer_id)
        ON DELETE RESTRICT
);
```

The composite foreign keys ensure that the source and destination
wallets use the transfer's currency. The application must still validate
that a reversal swaps the original wallets and uses the original amount.

### 6.6 IdempotencyRecord

``` sql
CREATE TABLE idempotency_records (
    user_id TEXT NOT NULL,

    idempotency_key TEXT NOT NULL
        CHECK (length(idempotency_key) BETWEEN 1 AND 64),

    request_fingerprint BLOB NOT NULL,

    transfer_id TEXT NOT NULL UNIQUE,
    created_at TEXT NOT NULL,

    PRIMARY KEY (user_id, idempotency_key),

    FOREIGN KEY (user_id)
        REFERENCES users(user_id),

    FOREIGN KEY (transfer_id)
        REFERENCES transfers(transfer_id)
        ON DELETE RESTRICT
);
```

The database length check does not by itself prove that the key contains
only ASCII characters. The application must validate the SRS's ASCII
requirement.

`transfer_id` is a proposed addition to support replay of the original
successful result.

### 6.7 LedgerEntry

``` sql
CREATE TABLE ledger_entries (
    entry_id TEXT PRIMARY KEY,

    sequence_no INTEGER NOT NULL UNIQUE
        CHECK (sequence_no > 0),

    transaction_id TEXT NOT NULL,
    account_id TEXT NOT NULL,
    currency_code TEXT NOT NULL,

    debit_minor INTEGER NOT NULL DEFAULT 0,
    credit_minor INTEGER NOT NULL DEFAULT 0,

    previous_hash BLOB NOT NULL,
    entry_hash BLOB NOT NULL,
    created_at TEXT NOT NULL,

    CHECK (
        typeof(debit_minor) = 'integer'
        AND typeof(credit_minor) = 'integer'
        AND (
            (debit_minor > 0 AND credit_minor = 0)
            OR
            (debit_minor = 0 AND credit_minor > 0)
        )
    ),

    FOREIGN KEY (transaction_id, currency_code)
        REFERENCES ledger_transactions(
            ledger_transaction_id,
            currency_code
        ),

    FOREIGN KEY (account_id, currency_code)
        REFERENCES ledger_accounts(
            account_id,
            currency_code
        )
);
```

This table-level CHECK enforces exactly one positive side per entry. It
does **not** prove that all entries in a transaction balance. That check
must be performed before the financial write commits.

### 6.8 IntegrityCheckpoint

``` sql
CREATE TABLE integrity_checkpoints (
    checkpoint_id INTEGER PRIMARY KEY
        CHECK (checkpoint_id = 1),

    chain_head_hash BLOB NOT NULL,

    last_entry_sequence INTEGER NOT NULL
        CHECK (last_entry_sequence >= 0),

    checkpoint_mac BLOB NOT NULL,
    updated_at TEXT NOT NULL
);
```

The `checkpoint_id = 1` check allows only one checkpoint row. The
application must initialise it with the agreed genesis state before
appending ledger entries.

## 7. Indexes and important constraints

Some uniqueness rules are already declared in the table definitions. The
following indexes support common lookups.

### 7.1 One reversal per original transfer

``` sql
CREATE UNIQUE INDEX uq_one_reversal_per_transfer
ON transfers(reversal_of_transfer_id)
WHERE reversal_of_transfer_id IS NOT NULL;
```

This enforces the SRS rule that an original transfer can have at most
one reversal.

### 7.2 Wallet lookups

``` sql
CREATE INDEX idx_wallets_user
ON wallets(user_id);
```

### 7.3 Ledger history and transaction lookups

``` sql
CREATE INDEX idx_ledger_entries_account_sequence
ON ledger_entries(account_id, sequence_no);

CREATE INDEX idx_ledger_entries_transaction
ON ledger_entries(transaction_id, sequence_no);

CREATE INDEX idx_ledger_entries_currency_sequence
ON ledger_entries(currency_code, sequence_no);
```

These support wallet ledger history, transaction entry retrieval and
currency reconciliation.

### 7.4 Transfer lookups

``` sql
CREATE INDEX idx_transfers_source_created
ON transfers(source_wallet_id, created_at);

CREATE INDEX idx_transfers_destination_created
ON transfers(destination_wallet_id, created_at);
```

### 7.5 Constraint summary

  -----------------------------------------------------------------------
  Rule                                Enforcement
  ----------------------------------- -----------------------------------
  One wallet per user per currency    UNIQUE constraint

  Wallet balance cannot be negative   CHECK constraint

  Wallet account refers to an         Foreign key
  existing wallet                     

  At most one wallet account per      UNIQUE constraint
  wallet                              

  Source and destination differ       CHECK constraint

  Transfer amount is positive         CHECK constraint

  Transfer wallets match transfer     Composite foreign keys
  currency                            

  One idempotency key per user        Composite primary key

  One reversal per original transfer  Partial unique index

  Entry has exactly one positive      CHECK constraint
  debit/credit side                   

  Entry refers to an account and      Composite foreign keys
  transaction of same currency        

  Ledger sequence number is unique    UNIQUE constraint
  -----------------------------------------------------------------------

## 8. Double-entry ledger

### 8.1 What the tables mean

-   **Wallet:** user's wallet and its stored balance.
-   **LedgerAccount:** the accounting account representing a wallet or
    system account.
-   **LedgerTransaction:** groups the entries for one financial
    operation.
-   **LedgerEntry:** records each debit or credit.

A single financial operation normally has at least two entries: a debit
and an equal credit.

The SRS defines a wallet balance as:

`Wallet balance = credits - debits on its ledger account`

### 8.2 Example: Alice sends ₹100 to Bob

Initial balances:

  Wallet     Balance
  -------- ---------
  Alice         ₹500
  Bob           ₹200

One LedgerTransaction is created:

  ledger_transaction_id   type       reference_id
  ----------------------- ---------- --------------
  LT1                     TRANSFER   T1

Two LedgerEntries are inserted:

  Transaction   Account                    Debit   Credit
  ------------- ------------------------ ------- --------
  LT1           Alice's wallet account     10000        0
  LT1           Bob's wallet account           0    10000

The values are paise.

The totals are equal:

`Total debit = 10000`

`Total credit = 10000`

The resulting balances are:

  Wallet   Calculation       New balance
  -------- --------------- -------------
  Alice    50000 - 10000            ₹400
  Bob      20000 + 10000            ₹300

The Transfer row stores the business details. The LedgerTransaction
groups the accounting operation. The LedgerEntry rows record the actual
movement.

### 8.3 Example: admin demo funding

Suppose the admin adds ₹500 to Alice's wallet.

The operation creates one funding LedgerTransaction and two entries:

  Account                    Debit   Credit
  ------------------------ ------- --------
  System funding account     50000        0
  Alice's wallet account         0    50000

The system funding account explains the other side of the wallet credit.
Funding must not be implemented as a direct wallet-balance edit.

### 8.4 Transaction-level balance validation

A row-level CHECK cannot compare the sums of multiple rows. Before
committing, the application must verify the transaction's entries:

``` sql
SELECT
    currency_code,
    SUM(debit_minor) AS total_debits,
    SUM(credit_minor) AS total_credits
FROM ledger_entries
WHERE transaction_id = ?
GROUP BY currency_code;
```

The operation must not commit unless the debit and credit totals are
equal for every currency represented. The design also expects the
transaction's entries to use one currency.

## 9. Immutability of ledger entries

FR-4.1 requires the database to reject UPDATE and DELETE operations on
committed ledger entries.

The proposed SQLite triggers are:

``` sql
CREATE TRIGGER prevent_ledger_entry_update
BEFORE UPDATE ON ledger_entries
BEGIN
    SELECT RAISE(ABORT, 'Ledger entries are immutable');
END;

CREATE TRIGGER prevent_ledger_entry_delete
BEFORE DELETE ON ledger_entries
BEGIN
    SELECT RAISE(ABORT, 'Ledger entries cannot be deleted');
END;
```

These triggers prevent ordinary SQL updates and deletes through the
database connection.

The ledger is append-only: new entries can be added, but old entries
cannot be rewritten.

The grouping records must also be immutable. Otherwise, someone could
change or delete a LedgerTransaction even while its LedgerEntry rows
remain unchanged. Add these triggers as well:

```sql
CREATE TRIGGER prevent_ledger_transaction_update
BEFORE UPDATE ON ledger_transactions
BEGIN
    SELECT RAISE(ABORT, 'Ledger transactions are immutable');
END;

CREATE TRIGGER prevent_ledger_transaction_delete
BEFORE DELETE ON ledger_transactions
BEGIN
    SELECT RAISE(ABORT, 'Ledger transactions cannot be deleted');
END;
```

**Security limitation:** SQLite triggers are not protection against a
person who can replace the database file or alter the database schema.
The hash chain and authenticated checkpoint provide additional tamper
detection. Database-file access and the external secret must also be
protected.

## 10. Reversals

FR-4.5 and FR-4.6 require a correction to be a new compensating
transaction. The original ledger entries remain unchanged.

### 10.1 Example

Alice originally sends ₹100 to Bob.

Original Transfer:

  transfer_id   source   destination     amount status
  ------------- -------- ------------- -------- -----------
  T1            Alice    Bob               ₹100 COMPLETED

Original entries:

  Account     Debit   Credit
  --------- ------- --------
  Alice        ₹100       ₹0
  Bob            ₹0     ₹100

Alice, as the original source-wallet owner, requests a reversal.

A new Transfer is created:

  --------------------------------------------------------------------------------------------
  transfer_id   source      destination           amount status      reversal_of_transfer_id
  ------------- ----------- ------------- -------------- ----------- -------------------------
  T2            Bob         Alice                   ₹100 COMPLETED   T1

  --------------------------------------------------------------------------------------------

A new LedgerTransaction and two new LedgerEntries are created:

  Account     Debit   Credit
  --------- ------- --------
  Bob          ₹100       ₹0
  Alice          ₹0     ₹100

The original T1 status changes from `COMPLETED` to `REVERSED`. Its
ledger entries do not change.

### 10.2 Reversal checks

Before a reversal is committed, the system must check:

1.  The original transfer exists and is eligible.
2.  The caller owns the original source wallet.
3.  The original transfer has not already been reversed.
4.  The original transfer is not itself a reversal.
5.  The new source is the original destination.
6.  The new destination is the original source.
7.  The amount equals the original full amount.
8.  The destination (now the reversal source) has enough funds, 
    so its balance does not become negative.

If the transfer is ineligible or funds are insufficient, the SRS
requires HTTP 409 and no database change.

The partial unique index on `reversal_of_transfer_id` enforces at most
one reversal per original transfer. Application validation is still
needed for ownership, eligibility, swapped wallets and equal amount.

## 11. Hash chain and integrity checkpoint

FR-4.3 and FR-4.4 require a secure hash chain and an external secret to
detect altered, inserted or deleted records. The admin integrity
operation recomputes the chain, checks it against the checkpoint and
reports the earliest mismatch.

### 11.1 Hash-chain idea

Each ledger entry stores:

-   `previous_hash`: the hash of the previous entry.
-   `entry_hash`: the hash calculated for this entry.

Conceptually:

`entry_hash = SHA-256(previous_hash || canonical_entry_data)`

The canonical entry data should include the fields that must be
protected:

-   sequence number
-   entry ID
-   transaction ID
-   account ID
-   currency code
-   debit amount
-   credit amount
-   UTC timestamp

The encoding must be deterministic and unambiguous. The exact encoding
and chain boundaries are TBD-05.

### 11.2 Chain example

``` text
Genesis hash
    |
    v
Entry 1: previous = Genesis, hash = H1
    |
    v
Entry 2: previous = H1,      hash = H2
    |
    v
Entry 3: previous = H2,      hash = H3
    |
    v
Checkpoint: chain head = H3, last sequence = 3
```

The first entry uses an agreed genesis hash because there is no earlier
entry.

**Proposed choice:** use SHA-256 for entry hashes and define a fixed
genesis value. The SRS does not specify the exact hash algorithm or
genesis value; these need approval.

### 11.3 IntegrityCheckpoint

The checkpoint stores:

-   the latest chain hash
-   the last entry sequence number
-   a checkpoint MAC

**Proposed choice:** use HMAC-SHA-256 to authenticate a canonical
checkpoint payload containing at least the sequence number and
chain-head hash. The HMAC key is an external secret and must not be
stored in SQLite or committed to the repository.

The exact MAC algorithm, key rotation and recovery approach remain
TBD-08.

### 11.4 Atomic checkpoint update

For a successful financial operation, the following changes must commit
together, as required by FR-2.4:

1.  Transfer or funding operation record.
2.  LedgerTransaction.
3.  All LedgerEntry rows.
4.  Wallet stored-balance updates.
5.  Successful IdempotencyRecord, where applicable.
6.  IntegrityCheckpoint update.

If any step fails, the entire database transaction must roll back.

### 11.5 Integrity verification

The admin integrity operation should:

1.  Read ledger entries in sequence order.
2.  Check that sequence numbers are continuous.
3.  Recompute each entry hash from its canonical data and previous hash.
4.  Compare the calculated hash with the stored `entry_hash`.
5.  Check that each entry's `previous_hash` matches the preceding
    entry's hash.
6.  Compare the final calculated hash and sequence with the checkpoint.
7.  Verify the checkpoint MAC using the external secret.
8.  Report the earliest mismatch.

### 11.6 Limitation and checkpoint protection

A hash chain alone is not enough to protect against an attacker who can
edit an old entry and recalculate every later hash.

The checkpoint MAC adds protection because a person who does not know
the external secret cannot create a valid MAC for a replacement
checkpoint.

However, if an attacker can replace both the database and the checkpoint
with an older valid copy, a locally stored checkpoint alone may not
reveal the rollback. A separately protected or externally retained
trusted checkpoint is a proposed additional safeguard.

The secret must remain outside the database and repository. Its storage,
backup and rotation are open decisions under TBD-08.

## 12. Reconciliation

Reconciliation checks whether the ledger's accounting totals and wallet
balances agree. It is an admin-only, read-only operation.

FR-5.1 to FR-5.4 define the required checks.

### 12.1 Check debit and credit totals per currency

``` sql
SELECT
    currency_code,
    SUM(debit_minor) AS total_debits,
    SUM(credit_minor) AS total_credits
FROM ledger_entries
GROUP BY currency_code;
```

For each currency, total debits must equal total credits.

Also check each LedgerTransaction individually. Otherwise, an imbalance
in one transaction could be hidden by an opposite imbalance in another.

``` sql
SELECT
    transaction_id,
    currency_code,
    SUM(debit_minor) AS total_debits,
    SUM(credit_minor) AS total_credits
FROM ledger_entries
GROUP BY transaction_id, currency_code
HAVING SUM(debit_minor) <> SUM(credit_minor);
```

A balanced ledger should return no rows from the second query.

### 12.2 Compare stored wallet balances with ledger balances

The ledger-derived wallet balance is:

`SUM(credit_minor - debit_minor)`

The following query compares it with the stored balance:

``` sql
SELECT
    w.wallet_id,
    w.user_id,
    w.currency_code,
    w.stored_balance_minor,

    COALESCE(
        SUM(le.credit_minor - le.debit_minor),
        0
    ) AS ledger_balance_minor,

    w.stored_balance_minor -
    COALESCE(
        SUM(le.credit_minor - le.debit_minor),
        0
    ) AS difference

FROM wallets w

LEFT JOIN ledger_accounts la
    ON la.wallet_id = w.wallet_id

LEFT JOIN ledger_entries le
    ON le.account_id = la.account_id

GROUP BY
    w.wallet_id,
    w.user_id,
    w.currency_code,
    w.stored_balance_minor;
```

A zero difference means the stored balance matches the ledger-derived
balance. A non-zero difference must be reported.

### 12.3 Consistent snapshot

FR-5.4 requires all reconciliation reads to use one consistent snapshot.

The proposed approach is to run the reconciliation queries inside one
SQLite read transaction:

``` sql
BEGIN DEFERRED;

-- Read debit and credit totals.
-- Read transaction-level balance exceptions.
-- Read wallet and ledger-derived balance comparisons.

COMMIT;
```

All reads in that transaction must use the same snapshot. This prevents
a concurrent commit from making different parts of the report reflect
different database states.

### 12.4 Reconciliation does not repair data

Reconciliation only reports:

-   debit and credit totals per currency
-   unbalanced transactions
-   stored wallet balances
-   ledger-derived wallet balances
-   differences and mismatches
-   overall result

It must not update wallets, ledger entries or checkpoints. Any
correction must be a separate valid financial transaction.

## 13. Concurrency and atomic financial writes

SQLite permits only one writer at a time. FR-3.2 requires the write lock
to be taken before the balance check.

The proposed write flow begins with:

``` sql
BEGIN IMMEDIATE;
```

Before BEGIN IMMEDIATE, the application performs authentication 
and ownership checks. These checks do not need the write lock.

For a transfer, the transaction then does the following:

1.  Checks the idempotency key and request fingerprint.
2.  Checks source and destination wallets and currency.
3.  Checks that the source has enough funds.
4.  Reads the current last sequence number and chain-head 
    hash.
5.  Creates the Transfer and LedgerTransaction.
6.  Creates the balanced LedgerEntry rows, using the 
    sequence number and hash values from step 4.
7.  Updates both stored wallet balances.
8.  Stores the successful idempotency result.
9.  Updates the integrity checkpoint.
10. Commits.

If any step fails, all changes roll back.

For a reversal, the same rules apply:

1.  Run the checks in Section 10.2.
2.  Take the write lock with BEGIN IMMEDIATE.
3.  Read the current chain head and last sequence number.
4.  Insert the reversal Transfer and its LedgerTransaction.
5.  Insert the two swapped LedgerEntries.
6.  Update both stored wallet balances.
7.  Change the original transfer’s status from COMPLETED to REVERSED.
8.  Update the integrity checkpoint.
9.  Commit.

For the same user and same key:

- Same request data returns the original successful result.
- Different request data returns HTTP 409.
- Concurrent requests using the same key must not create duplicate
  transfers.

A new intentional payment uses a new idempotency key, even if its amount
and destination are the same.

**Insert order matters:** SQLite checks foreign keys immediately by
default. Within the write transaction, insert parent rows before child
rows: create wallets before transfers, transfers before idempotency
records, and LedgerTransactions before LedgerEntries. If child rows are
inserted first, the foreign-key checks will fail and the transaction
will not complete.

**Sequence assignment:** Assign each new LedgerEntry its `sequence_no`
inside the same write transaction, after acquiring the write lock. Use
the current highest sequence number plus one (or zero plus one when the
ledger is empty). Because writes are serialised and the sequence
assignment and inserts are in the same transaction, a rolled-back
operation does not leave a committed sequence gap or cause two writers
to claim the same number.

`SQLITE_BUSY` handling, timeout and retry settings are not finalised;
see TBD-03 and TBD-06. Retries of the same request must retain the same
idempotency key.

## 14. Ledger history

FR-4.7 requires chronological pages of up to 100 entries.

The sequence number provides a stable ordering. A query can use keyset
pagination:

``` sql
SELECT *
FROM ledger_entries
WHERE account_id = ?
  AND sequence_no > ?
ORDER BY sequence_no
LIMIT 100;
```

The first page can use a starting sequence of zero. The next page uses
the last sequence returned by the previous page.

If a client asks for more than 100 entries, the server returns at most 100.

The endpoint must enforce wallet ownership before returning entries.

## 15. SRS decisions versus proposed decisions

### 15.1 Existing SRS TBDs

  SRS TBD   Open question
  --------- ---------------------------------------------------------
  TBD-01    Supported currencies and minor-unit scales
  TBD-02    Final JSON schemas and OpenAPI definitions
  TBD-03    Spring/SQLite write-lock integration
  TBD-04    Idempotency-record retention policy
  TBD-05    Ledger-entry encoding and hash-chain boundaries
  TBD-06    SQLite durability mode, busy timeout and retry settings
  TBD-07    Demo-funding limits and test-data reset
  TBD-08    HMAC algorithm, key rotation and recovery

### 15.2 Additional proposed database decisions

  -----------------------------------------------------------------------
  ID                      Detail not fully        Proposal in this
                          specified               document
  ----------------------- ----------------------- -----------------------
  DB-01                   UUID storage            Store UUIDs as TEXT
                          representation          

  DB-02                   Timestamp storage       Store UTC timestamps as
                          representation          TEXT

  DB-03                   Hash and MAC storage    Store as BLOB
                          representation          

  DB-04                   Wallet-to-account       Exactly one wallet
                          mapping                 LedgerAccount per
                                                  wallet

  DB-05                   Transfer wallet columns Separate source and
                                                  destination wallet IDs

  DB-06                   Funding reference       Use a unique
                                                  funding-operation
                                                  identifier in
                                                  `reference_id`

  DB-07                   Idempotency result      Add `transfer_id` to
                          lookup                  IdempotencyRecord

  DB-08                   LedgerTransaction       Add `currency_code` to
                          currency                support currency
                                                  consistency

  DB-09                   LedgerEntry identifier  Add a UUID `entry_id`
                                                  primary key

  DB-10                   Checkpoint row identity Use one singleton
                                                  checkpoint row

  DB-11                   Account types           Initially support
                                                  WALLET and
                                                  SYSTEM_FUNDING

  DB-12                   Chain boundary          Propose one global
                                                  chain

  DB-13                   Hash algorithm and      Propose SHA-256 and a
                          genesis value           fixed genesis value

  DB-14                   Checkpoint MAC          Propose HMAC-SHA-256
                          algorithm               

  DB-15                   Exact response replay   Reconstruct from
                                                  transfer or decide
                                                  whether response data
                                                  must also be stored
  -----------------------------------------------------------------------

These are proposals for review, not claims that the SRS mandates those
exact choices.

## 16. Database test checklist

The following tests should be included in database and integration
testing.

  -----------------------------------------------------------------------
  Test                                Expected result
  ----------------------------------- -----------------------------------
  Create two wallets for one user in  Second wallet rejected
  the same currency                   

  Create wallets for one user in two  Both accepted
  supported currencies                

  Create wallet for a nonexistent     Rejected
  user                                

  Create wallet                       Stored balance starts at zero

  Insert negative stored balance      Rejected

  Transfer zero or negative amount    Rejected

  Transfer between different          Rejected
  currencies                          

  Transfer from a wallet without      Rejected; no partial changes
  sufficient funds                    

  Insert ledger entry with both debit Rejected
  and credit positive                 

  Insert ledger entry with both debit Rejected
  and credit zero                     

  Insert entry with invalid account   Rejected
  or transaction                      

  Insert entry with currency          Rejected
  different from account/transaction  

  Update a committed ledger entry     Rejected

  Delete a committed ledger entry     Rejected

  Retry same user, same key and same  Original result replayed; no new
  data                                transfer

  Reuse same key with different data  HTTP 409

  Use a new key for a new intentional New transfer can be created if
  transfer                            valid

  Create two reversals for one        Second reversal rejected
  transfer                            

  Attempt to reverse a reversal       Rejected

  Reverse when destination lacks      HTTP 409; no change
  funds                               

  Fail a write after some steps       Full rollback, including checkpoint

  Roll back a transfer after assigning No committed sequence gap remains
  ledger sequence numbers

  Update a row in ledger_transactions  Rejected by immutability trigger

  Delete a row in ledger_transactions  Rejected by immutability trigger

  Reconcile a balanced ledger         PASS

  Reconcile mismatched wallet balance Discrepancy reported; no repair

  Alter an entry                      Integrity verification detects
                                      mismatch

  Run concurrent transfers on         No overspending, lost updates or
  separate connections                duplicate execution
  -----------------------------------------------------------------------

## 17. Decisions for team review

Before implementation, the team should review and agree on:

1.  The exact relational schema and proposed extra columns.
2.  The funding-operation reference approach.
3.  How exact idempotent response replay will work.
4.  The global hash-chain boundary and canonical entry encoding.
5.  The hash algorithm, genesis value and checkpoint MAC.
6.  External-secret storage, rotation and recovery.
7.  SQLite write-lock integration, durability, timeout and retry
    settings.
8.  Currency configuration and supported minor-unit precision.

## 18. References

### Requirements source

-   **Financial Ledger & Digital Wallet API --- SRS v1.3**
    -   Section 2.4: Operating Environment
    -   Section 2.5: Design and Implementation Constraints
    -   Section 4.1: Wallet Initialization
    -   Section 4.2: Idempotent Transfers
    -   Section 4.3: Concurrent Balance Mutability
    -   Section 4.4: Immutable Audit Trail and Reversals
    -   Section 4.5: Ledger Reconciliation
    -   Section 6.1: Monetary Representation and Database
    -   Appendix B: Analysis Models, entity list and ER diagram
    -   Appendix C: To Be Determined List

### Related Brain notes

These notes exist in `brain/resources/`:

-   `[[sqlite-acid-transactions-final]]`
-   `[[sqlite-wal-mode]]`
-   `[[monetary-precision]]`
-   `[[race-conditions]]`
-   `[[immutable-audit-trails-and-reversals]]`
-   `[[double-entry-accounting]]`
-   `[[ledger-reconciliation]]`

These links should point to actual distilled notes in the team's Second
Brain.