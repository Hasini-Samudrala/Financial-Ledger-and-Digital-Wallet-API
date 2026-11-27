CREATE TABLE IF NOT EXISTS users (
    user_id TEXT PRIMARY KEY,
    username TEXT NOT NULL,
    api_key_hash BLOB NOT NULL,
    api_key_salt BLOB NOT NULL
);

CREATE UNIQUE INDEX IF NOT EXISTS uq_users_username_lower
ON users(LOWER(username));

CREATE TABLE IF NOT EXISTS wallets (
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

CREATE TABLE IF NOT EXISTS ledger_accounts (
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

CREATE UNIQUE INDEX IF NOT EXISTS uq_system_funding_currency
ON ledger_accounts(currency_code)
WHERE account_type = 'SYSTEM_FUNDING';

CREATE TABLE IF NOT EXISTS ledger_transactions (
    ledger_transaction_id TEXT PRIMARY KEY,
    type TEXT NOT NULL
        CHECK (type IN ('FUNDING', 'TRANSFER', 'REVERSAL')),
    reference_id TEXT NOT NULL,
    currency_code TEXT NOT NULL,
    created_at TEXT NOT NULL,
    UNIQUE (type, reference_id),
    UNIQUE (ledger_transaction_id, currency_code)
);

CREATE TABLE IF NOT EXISTS transfers (
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

CREATE UNIQUE INDEX IF NOT EXISTS uq_one_reversal_per_transfer
ON transfers(reversal_of_transfer_id)
WHERE reversal_of_transfer_id IS NOT NULL;

CREATE TABLE IF NOT EXISTS idempotency_records (
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

CREATE TABLE IF NOT EXISTS ledger_entries (
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
        REFERENCES ledger_transactions(ledger_transaction_id, currency_code),
    FOREIGN KEY (account_id, currency_code)
        REFERENCES ledger_accounts(account_id, currency_code)
);

CREATE TABLE IF NOT EXISTS integrity_checkpoints (
    checkpoint_id INTEGER PRIMARY KEY
        CHECK (checkpoint_id = 1),
    chain_head_hash BLOB NOT NULL,
    last_entry_sequence INTEGER NOT NULL
        CHECK (last_entry_sequence >= 0),
    checkpoint_mac BLOB NOT NULL,
    updated_at TEXT NOT NULL
);

CREATE INDEX IF NOT EXISTS idx_wallets_user
ON wallets(user_id);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_account_sequence
ON ledger_entries(account_id, sequence_no);

CREATE INDEX IF NOT EXISTS idx_ledger_entries_transaction
ON ledger_entries(transaction_id, sequence_no);

CREATE TRIGGER IF NOT EXISTS prevent_ledger_entry_update
BEFORE UPDATE ON ledger_entries
BEGIN
    SELECT RAISE(ABORT, 'Ledger entries are immutable');
END;

CREATE TRIGGER IF NOT EXISTS prevent_ledger_entry_delete
BEFORE DELETE ON ledger_entries
BEGIN
    SELECT RAISE(ABORT, 'Ledger entries cannot be deleted');
END;

CREATE TRIGGER IF NOT EXISTS prevent_ledger_transaction_update
BEFORE UPDATE ON ledger_transactions
BEGIN
    SELECT RAISE(ABORT, 'Ledger transactions are immutable');
END;

CREATE TRIGGER IF NOT EXISTS prevent_ledger_transaction_delete
BEFORE DELETE ON ledger_transactions
BEGIN
    SELECT RAISE(ABORT, 'Ledger transactions cannot be deleted');
END;
