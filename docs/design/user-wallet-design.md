# User, Authentication and Wallet Management Design

- **Project:** Financial Ledger & Digital Wallet API
- **Document:** `user-wallet-design.md`
- **Source baseline:** SRS v1.3
- **Related designs:** `database-ledger-design.md`, `transfer-and-concurrency-design.md`
- **Status:** Proposed technical design for team review
- **Owner:** Hasini
- **Scope:** User registration, API key authentication, wallet creation, wallet retrieval, ownership validation and wallet ledger-history access
- **Implementation status:** Design only; application implementation is out of scope

---

## 1. Purpose

This document describes the proposed design for user registration, API key authentication and wallet management in the Financial Ledger & Digital Wallet API.
The system allows a client to register as a user, receive an API key, create currency-specific wallets and retrieve wallet information. Every user must be able to access only their own wallets and the associated financial information.
The design follows the requirements specified in SRS v1.3.

The main responsibilities covered by this document are:

1. Registering a new user.
2. Generating and securely storing user API keys.
3. Authenticating requests using `X-User-API-Key`.
4. Creating one wallet per user per supported currency.
5. Creating the corresponding ledger account for each wallet.
6. Retrieving wallet details and current balances.
7. Checking wallet ownership before allowing access.
8. Retrieving wallet ledger history.
9. Handling invalid requests and authorization failures.
10. Defining unit and integration tests for these operations.

This document does not redefine the financial rules, database schema or transfer-processing behaviour already specified in the SRS and the other design documents.

### 1.1 Design principles

The design follows these principles:

- The SRS v1.3 is the requirements baseline.
- User API keys are returned only once and are never stored in plaintext.
- A user's identity is determined from the API key, not from an untrusted user ID supplied in a request body.
- A user can create and access only their own wallets.
- A wallet belongs to exactly one user and one currency.
- A user can have at most one wallet for a supported currency.
- Every newly created wallet starts with a zero balance.
- Every wallet has exactly one corresponding ledger account.
- Wallet balances change only through valid ledger-backed financial operations.
- Financial amounts use integer minor units, never floating-point values.
- Database constraints and application-level validation work together to enforce the rules.

---

## 2. Requirements Covered

The design primarily addresses the following requirements from SRS v1.3.

| Requirement | Design responsibility |
|---|---|
| FR-1.1 | Register a user and return a generated API key once |
| FR-1.2 | Allow an authenticated user to create one wallet per supported currency |
| FR-1.3 | Ensure a new wallet starts at zero and its balance is not directly edited |
| FR-1.5 | Validate UUID identifiers |
| NFR-5 | Validate inputs and use parameterised SQL |
| NFR-6 | Store only salted hashes of user API keys |
| NFR-7 | Restrict wallet, ledger and transfer access to the appropriate owner |
| BR-1 | Enforce one wallet per user per currency |
| BR-4 | Ensure stored balances agree with ledger-derived balances |

The following API endpoints are covered:

| Method | Endpoint | Responsibility |
|---|---|---|
| POST | `/users` | Register a user |
| POST | `/users/{userId}/wallets` | Create a currency wallet |
| GET | `/wallets/{walletId}` | Retrieve wallet details and balance |
| GET | `/wallets/{walletId}/ledger-entries` | Retrieve the owner's ledger history |

The following operations are discussed only where they depend on authentication or wallet ownership:

- `POST /transfers`
- `GET /transfers/{transferId}`
- `POST /transfers/{transferId}/reversal`
- `POST /admin/funding`

Their financial processing rules are defined in the transfer/concurrency and database/ledger designs.

---

## 3. Overall Design

The application follows a layered Spring Boot architecture.

The user and wallet operations are separated into the following responsibilities:

1. **Controller layer:** Receives HTTP requests, reads headers and path parameters, and returns HTTP responses.
2. **Service layer:** Performs registration, authentication-related business checks, wallet creation and ownership validation.
3. **Repository layer:** Executes database queries and updates using JDBC.
4. **Database layer:** Stores users, wallets, ledger accounts and ledger entries.

### 3.1 Component responsibilities

| Component | Responsibility |
|---|---|
| UserController | Handles user registration |
| WalletController | Handles wallet creation and wallet retrieval |
| LedgerController | Handles wallet ledger-history requests |
| UserService | Registers users and coordinates API key generation |
| ApiKeyService | Generates API keys and verifies credentials |
| WalletService | Creates wallets and validates ownership |
| LedgerHistoryService | Retrieves ledger history for an authorized wallet |
| UserRepository | Reads and inserts user records |
| WalletRepository | Creates and retrieves wallet records |
| LedgerAccountRepository | Creates the ledger account associated with a wallet |
| LedgerEntryRepository | Retrieves ledger entries for a wallet's ledger account |

These are logical responsibilities. The final class names and package structure can be adjusted during implementation, provided the separation of concerns is maintained.

### 3.2 Request-processing flow

For a user-protected endpoint, the request follows this general flow:

1. The client sends an HTTP request with `X-User-API-Key`.
2. The application extracts the API key.
3. The API key is validated.
4. The authenticated user's identity is established.
5. The requested resource is retrieved or created.
6. Ownership and business rules are checked.
7. The service performs the required operation.
8. The controller returns the appropriate JSON response.

The application must never trust a client-supplied user ID as proof of identity.

---

## 4. User Registration

### 4.1 Endpoint

`POST /users`

User registration is available for the local academic prototype.

As specified in FR-1.1 and NFR-6, a newly registered user receives a generated API key. The key is shown only once in the creation response.

Registration does not require an existing user API key.

### 4.2 Registration process

When a client sends a registration request:

1. The controller receives `POST /users`.
2. The UserService generates a new UUID for the user.
3. The ApiKeyService generates a cryptographically secure random API key.
4. A salt is generated for the API key.
5. A one-way salted hash of the API key is calculated.
6. The UserRepository stores the user ID, hash and salt.
7. The original API key is returned to the client in the response.
8. The plaintext API key is not stored or logged.

The user record must be inserted successfully before the API key is returned as a successful registration result.

If database insertion fails, the registration operation must not report success.

### 4.3 API key generation

The API key must be generated using a cryptographically secure random number generator.

It must contain sufficient randomness to make guessing impractical.

The API key is a credential used to authenticate subsequent requests. It is not a password that the client should send in request bodies.

**Proposed API key representation for team review:**

The API key may contain:

- A public user identifier used to locate the corresponding user record.
- A randomly generated secret portion used to authenticate the request.

For example, a conceptual representation could be:

`flw_<userId>.<randomSecret>`

This is a proposed lookup approach, not a format mandated by the SRS.

The public identifier is not an authentication secret. The server must still verify the secret portion against the stored salted hash before accepting the request.

This approach allows the application to locate the user record using the public identifier without storing the original API key or performing a search across every user's salted hash.

The final API key format must be agreed upon by the team before implementation.

### 4.4 Secure API key storage

The database must never store the original API key.

Only the following information is stored:

| Field | Purpose |
|---|---|
| `user_id` | Identifies the registered user |
| `api_key_hash` | Stores the one-way salted hash of the API key |
| `api_key_salt` | Stores the salt used when calculating the hash |

These fields match the `User` entity specified in Appendix B of SRS v1.3 and Perla's database design.

The hash must be calculated using an appropriate secure one-way password-hashing or key-derivation approach.

The exact algorithm and parameters must be finalized as an implementation decision. The SRS requires salted one-way hashing but does not prescribe a specific algorithm.

The plaintext API key must not be:

- Stored in the database.
- Written to application logs.
- Included in exception messages.
- Committed to GitHub.
- Included in AI prompts or Second Brain notes.

### 4.5 Registration request

No mandatory request fields are specified for registration in SRS v1.3.

Therefore, the registration request can have an empty JSON body.

Example:

```http
POST /users
Content-Type: application/json
```

```json
{}
```

### 4.6 Registration response

A successful registration returns HTTP 201 Created.

Example:

```json
{
  "userId": "7c6b8e3a-2d15-4c89-a123-456789abcdef",
  "apiKey": "flw_7c6b8e3a-2d15-4c89-a123-456789abcdef.randomSecret"
}
```

The example API key format is illustrative and remains subject to team approval.

The actual response must contain the generated credential only once. Subsequent requests to retrieve user information must not return the original API key.

### 4.7 Registration validation and errors

| Situation | Expected behaviour |
|---|---|
| Registration succeeds | HTTP 201 Created |
| User identifier generation fails | Registration fails; no successful response |
| Database insertion fails | No user is reported as successfully registered |
| Unexpected server error | Return a safe error response without exposing internal details |

The SRS does not prescribe a specific registration rate limit or duplicate-registration policy. Such policies must not be introduced as mandatory requirements without team agreement.

---

## 5. API Key Authentication

### 5.1 Purpose

API key authentication identifies the user making a request.

Every user-protected wallet, ledger-history, transfer and reversal operation must use the `X-User-API-Key` header.

The system uses the API key to establish the authenticated user's identity before processing the requested operation.

### 5.2 Authentication header

Example:

```http
X-User-API-Key: flw_<userId>.<randomSecret>
```

The actual key format is subject to the proposed design decision in Section 4.3.

The API key must be sent through the HTTP header rather than through a query parameter or request body.

### 5.3 Authentication process

For a protected request, the authentication process is:

1. Read the `X-User-API-Key` header.
2. Check whether the header is present.
3. Validate the API key's expected structure.
4. Extract the public lookup identifier, if the proposed key format is adopted.
5. Retrieve the corresponding user record.
6. Read the stored `api_key_hash` and `api_key_salt`.
7. Calculate the hash of the supplied secret using the stored salt and agreed hashing algorithm.
8. Compare the calculated hash with the stored hash using a constant-time comparison where supported.
9. If the values match, establish the authenticated user identity.
10. If validation fails, reject the request.

The original API key must not be stored or reconstructed during this process.

### 5.4 Authentication outcomes

| Situation | HTTP status | Behaviour |
|---|---:|---|
| Valid API key | Continue | Establish authenticated user identity |
| Missing API key | 401 | Reject request |
| Invalid API key | 401 | Reject request |
| Unknown user identifier | 401 | Reject request |
| Incorrect secret portion | 401 | Reject request |

Invalid credentials must not reveal whether a particular user ID exists.

### 5.5 Authentication and authorization are different

Authentication answers:

**Who is making this request?**

Authorization answers:

**Is this authenticated user allowed to access the requested wallet?**

For example:

- A valid API key belonging to Alice establishes Alice as the authenticated user.
- If Alice requests one of her own wallets, the ownership check succeeds.
- If Alice requests Bob's wallet, authentication succeeds but authorization fails.

In the second case, the system returns HTTP 403 Forbidden.

This distinction is important because a valid API key does not grant access to every wallet in the system.

### 5.6 User identity must come from authentication

For protected operations, the application must use the authenticated user ID obtained from the API key.

It must not rely on a user ID supplied by the client as the identity of the caller.

For wallet creation, the authenticated user ID must match the `{userId}` path parameter.

For wallet retrieval and ledger-history access, the authenticated user ID must match the owner of the requested wallet.

This enforces FR-1.2 and NFR-7.

---

## 6. Wallet Creation

### 6.1 Endpoint

`POST /users/{userId}/wallets`

This endpoint creates a new wallet for an authenticated user.

A user can create one wallet for each supported currency.

A user cannot create multiple wallets for the same currency.

### 6.2 Wallet creation rules

The following rules must be enforced:

1. The request must contain a valid `X-User-API-Key`.
2. The authenticated user's ID must match the `{userId}` in the URL.
3. The requested currency must be supported.
4. The user must not already have a wallet for that currency.
5. The new wallet must start with a balance of zero.
6. The new wallet must have exactly one corresponding LedgerAccount.
7. The wallet and its LedgerAccount must be created atomically.

These rules follow FR-1.2, FR-1.3 and BR-1.

### 6.3 Wallet creation workflow

The proposed processing sequence is:

1. Receive the request at WalletController.
2. Authenticate the user using `X-User-API-Key`.
3. Validate the `{userId}` path parameter.
4. Compare the path user ID with the authenticated user ID.
5. If they do not match, return HTTP 403.
6. Validate the requested currency code.
7. Check whether the user already has a wallet for that currency.
8. Generate a new wallet UUID.
9. Generate a new ledger account UUID.
10. Insert the Wallet record with `stored_balance_minor = 0`.
11. Insert the corresponding LedgerAccount record.
12. Commit both records together.
13. Return the new wallet information.

The wallet and ledger account must not be created independently.

If either insertion fails, the transaction must roll back so that the database does not contain a wallet without its corresponding ledger account, or a ledger account without its wallet.

### 6.4 Wallet database fields

The Wallet entity follows the database design prepared by Perla.

| Field | Type | Purpose |
|---|---|---|
| `wallet_id` | TEXT | Unique wallet UUID; primary key |
| `user_id` | TEXT | Identifies the wallet owner; foreign key to User |
| `currency_code` | TEXT | Currency associated with the wallet |
| `stored_balance_minor` | INTEGER | Current stored balance in minor units |

Important constraints:

- `wallet_id` is the primary key.
- `user_id` must reference an existing User.
- `currency_code` must not be null.
- `stored_balance_minor` must be an integer.
- `stored_balance_minor` must not be negative.
- `(user_id, currency_code)` must be unique.

The uniqueness constraint ensures that the same user cannot create two wallets for the same currency, even if two creation requests arrive close together.

The application should handle a uniqueness-constraint violation and return HTTP 409 Conflict.

### 6.5 Corresponding LedgerAccount

A wallet is the user-facing representation of stored funds. A LedgerAccount represents the wallet in the accounting system.

Each wallet must have exactly one corresponding LedgerAccount.

The LedgerAccount entity follows Perla's database design:

| Field | Purpose |
|---|---|
| `account_id` | Unique ledger account identifier |
| `account_type` | Identifies the account type |
| `wallet_id` | References the associated wallet |
| `currency_code` | Currency of the account |

For a user wallet, the proposed account type is `WALLET`.

The LedgerAccount must reference the newly created wallet and use the same currency.

The system funding account is a separate system account. It is not created as a user's wallet.

The wallet-to-ledger-account relationship is one-to-one.

### 6.6 Initial balance

A newly created wallet starts with:

`stored_balance_minor = 0`

No funds are automatically added during wallet creation.

The wallet's balance must not be initialized using a direct balance edit or an artificial funding entry.

If a user needs demo funds, the administrator must use the separate `/admin/funding` operation.

That operation creates a balanced ledger transaction with:

- A debit to the system funding account.
- An equal credit to the user's wallet account.

This behaviour is defined by FR-1.3 and FR-1.4 and is covered in the database/ledger design.

### 6.7 Wallet creation request

Example:

```http
POST /users/7c6b8e3a-2d15-4c89-a123-456789abcdef/wallets
X-User-API-Key: <user-api-key>
Content-Type: application/json
```

```json
{
  "currencyCode": "INR"
}
```

`INR` is an illustrative currency. The final supported currency list and minor-unit scales are TBD-01 in the SRS.

### 6.8 Wallet creation response

A successful wallet creation returns HTTP 201 Created.

Example:

```json
{
  "walletId": "a9d6f5c1-1b20-4f63-a123-456789abcdef",
  "userId": "7c6b8e3a-2d15-4c89-a123-456789abcdef",
  "currencyCode": "INR",
  "balanceMinor": 0
}
```

The response uses integer minor units for the balance.

The JSON field names are proposed examples. The final request and response schema must be agreed upon under TBD-02.

### 6.9 Wallet creation errors

| Situation | HTTP status | Reason |
|---|---:|---|
| Wallet created successfully | 201 | Wallet and ledger account created |
| Missing or invalid API key | 401 | Authentication failed |
| Authenticated user ID differs from path user ID | 403 | User cannot create a wallet for another user |
| Malformed user UUID | 400 | Invalid identifier |
| Unsupported currency | 400 | Currency is not supported |
| Wallet already exists for the same user and currency | 409 | Duplicate wallet |
| Database contention exceeds configured wait | 503 | Database operation could not complete |
| Unexpected server error | Appropriate server error | No internal details exposed |

A failed wallet creation must not leave partial database records.

---

## 7. Wallet Retrieval and Balance Access

### 7.1 Endpoint

`GET /wallets/{walletId}`

This endpoint retrieves the details and current stored balance of a wallet.

Only the authenticated owner of the wallet may access it.

### 7.2 Retrieval workflow

The processing sequence is:

1. Receive the wallet ID from the URL.
2. Authenticate the caller using `X-User-API-Key`.
3. Validate the wallet ID format.
4. Retrieve the wallet record.
5. If the wallet does not exist, return HTTP 404.
6. Compare the wallet's `user_id` with the authenticated user ID.
7. If they differ, return HTTP 403.
8. Retrieve the wallet's stored balance and currency.
9. Return the wallet information.

The ownership check must happen before returning wallet information.

### 7.3 Balance representation

The balance is represented using a 64-bit integer number of minor units.

For example, in a two-decimal currency:

| Display amount | Stored balance |
|---|---:|
| ₹100.00 | 10000 |
| ₹25.50 | 2550 |
| ₹0.01 | 1 |

The examples are illustrative. Currency precision depends on the supported currency configuration.

The application must not use `float` or `double` for monetary values.

Excess currency precision must be rejected rather than rounded.

### 7.4 Stored balance and ledger-derived balance

The system maintains a stored balance for efficient wallet retrieval.

However, the ledger remains the authoritative financial history.

The balance is defined by the SRS as:

`Wallet balance = total credits - total debits on its LedgerAccount`

The stored balance must agree with the balance derived from the corresponding LedgerAccount.

The stored balance is updated atomically with the relevant ledger entries during financial operations.

It must never be independently modified through the wallet retrieval or wallet management APIs.

Reconciliation is responsible for checking whether stored balances agree with ledger-derived balances.

The reconciliation operation is described in the SRS and database/ledger design, not implemented through this endpoint.

### 7.5 Wallet retrieval response

Example:

```http
GET /wallets/a9d6f5c1-1b20-4f63-a123-456789abcdef
X-User-API-Key: <user-api-key>
```

Successful response:

```json
{
  "walletId": "a9d6f5c1-1b20-4f63-a123-456789abcdef",
  "userId": "7c6b8e3a-2d15-4c89-a123-456789abcdef",
  "currencyCode": "INR",
  "balanceMinor": 10000
}
```

The example represents a stored balance of ₹100.00 in a two-decimal currency.

The exact response schema remains subject to TBD-02.

### 7.6 Wallet retrieval errors

| Situation | HTTP status | Reason |
|---|---:|---|
| Wallet retrieved successfully | 200 | Read succeeded |
| Missing or invalid API key | 401 | Authentication failed |
| Malformed wallet UUID | 400 | Invalid identifier |
| Wallet does not exist | 404 | Unknown resource |
| Wallet belongs to another user | 403 | Ownership check failed |

The response must not disclose another user's wallet information.

---

## 8. Wallet Ledger History

### 8.1 Endpoint

`GET /wallets/{walletId}/ledger-entries`

This endpoint allows an authenticated wallet owner to retrieve the financial history associated with their wallet.

The endpoint returns ledger entries, not editable transaction records.

### 8.2 Access control

Before retrieving the ledger history, the system must:

1. Authenticate the user.
2. Validate the wallet UUID.
3. Retrieve the wallet.
4. Check that the authenticated user owns the wallet.
5. Retrieve the corresponding LedgerAccount.
6. Retrieve the ledger entries belonging to that account.

If the caller does not own the wallet, the request returns HTTP 403.

The system must not expose ledger history belonging to another user.

### 8.3 Ledger history retrieval

Each wallet is associated with exactly one LedgerAccount.

The ledger entries are retrieved using that account's identifier.

The database design specifies the fields stored in a LedgerEntry, including:

- `sequence_no`
- `transaction_id`
- `account_id`
- `currency_code`
- `debit_minor`
- `credit_minor`
- `previous_hash`
- `entry_hash`
- `created_at`

The ledger-history API returns the relevant entries in chronological order.

As required by FR-4.7:

- Entries must be returned chronologically.
- A page must contain at most 100 entries.
- Pagination must be supported for larger histories.

The exact pagination parameters and response structure are part of TBD-02 and must be agreed upon with the team.

### 8.4 Example request

```http
GET /wallets/a9d6f5c1-1b20-4f63-a123-456789abcdef/ledger-entries
X-User-API-Key: <user-api-key>
```

### 8.5 Illustrative response

```json
{
  "walletId": "a9d6f5c1-1b20-4f63-a123-456789abcdef",
  "entries": [
    {
      "sequenceNo": 1,
      "transactionId": "lt-123",
      "currencyCode": "INR",
      "debitMinor": 0,
      "creditMinor": 10000,
      "createdAt": "2026-11-19T10:30:00Z"
    }
  ],
  "nextPage": null
}
```

This response is illustrative. The exact pagination format and whether hash fields are exposed through the public API require team agreement.

The public response must not expose internal secrets or checkpoint authentication material.

### 8.6 Ledger immutability

Ledger entries are append-only.

The wallet and ledger-history APIs must not provide an operation to update or delete committed ledger entries.

Corrections are made through new compensating transactions, as specified by FR-4.5 and FR-4.6.

The original ledger entries remain unchanged.

The database-level immutability rules and hash-chain verification are covered in Perla's database/ledger design.

---

## 9. Authorization Across the API

Authentication and ownership checks must be consistently applied across all user-protected endpoints.

The following table summarizes the access rules.

| Endpoint | Required credential | Authorization rule |
|---|---|---|
| `POST /users` | None | Local prototype registration |
| `POST /users/{userId}/wallets` | `X-User-API-Key` | Authenticated user ID must equal path user ID |
| `GET /wallets/{walletId}` | `X-User-API-Key` | Caller must own the wallet |
| `GET /wallets/{walletId}/ledger-entries` | `X-User-API-Key` | Caller must own the wallet |
| `POST /transfers` | `X-User-API-Key` | Caller must own the source wallet |
| `GET /transfers/{transferId}` | `X-User-API-Key` | Caller must own source or destination wallet |
| `POST /transfers/{transferId}/reversal` | `X-User-API-Key` | Caller must own the original source wallet |
| `POST /admin/funding` | `X-Admin-API-Key` | Administrator only |
| `GET /admin/reconciliation` | `X-Admin-API-Key` | Administrator only |
| `GET /admin/ledger-integrity` | `X-Admin-API-Key` | Administrator only |

The transfer and reversal operations are implemented according to the separate transfer/concurrency design.

The admin key is supplied through configuration and is not stored in the User table or committed to the repository.

### 9.1 Important authorization rules

- A valid user API key does not grant access to another user's wallet.
- A client cannot create a wallet for another user by changing the URL.
- A client cannot retrieve another user's ledger history.
- Transfer access is limited to the source or destination wallet owners.
- Only the owner of the original source wallet may request a reversal.
- Administrator operations require the separate admin credential.

These rules follow FR-1.2, FR-2.9, NFR-6 and NFR-7.

---

## 10. Validation and Error Handling

All request values must be validated before they are used in database operations.

SQL queries must use parameterised statements.

Errors must use the status codes specified in Section 3.4 of SRS v1.3.

### 10.1 Identifier validation

All identifiers use UUIDs, as required by FR-1.5.

The application validates UUID formats before executing database queries.

Examples:

- Valid UUID: `7c6b8e3a-2d15-4c89-a123-456789abcdef`
- Invalid UUID: `user-123`

A malformed identifier returns HTTP 400.

### 10.2 Currency validation

The requested currency must be present in the supported currency configuration.

An unsupported currency returns HTTP 400.

The supported currencies and their minor-unit precision are not yet fixed. This is TBD-01.

The application must not silently accept an unsupported currency or round an amount to fit the currency's precision.

### 10.3 Ownership validation

Ownership is checked by comparing:

`authenticatedUserId == wallet.user_id`

If the values do not match, the request returns HTTP 403.

For wallet creation, the equivalent check is:

`authenticatedUserId == pathUserId`

### 10.4 Standard error response

The SRS requires consistent JSON errors and prohibits disclosure of internal details.

A proposed error structure is:

```json
{
  "error": {
    "code": "WALLET_NOT_FOUND",
    "message": "The requested wallet does not exist."
  }
}
```

The final JSON error format is subject to TBD-02.

Error messages must not expose:

- SQL queries.
- Database file paths.
- Stack traces.
- API keys or salts.
- Internal hashing information.
- Other users' private information.

### 10.5 Status-code summary

| HTTP status | Meaning | Example |
|---|---|---|
| 200 | Successful read | Wallet or ledger history retrieved |
| 201 | Resource created | User or wallet registered |
| 400 | Invalid input | Malformed UUID or unsupported currency |
| 401 | Authentication failed | Missing or invalid API key |
| 403 | Authorization failed | Caller does not own the wallet |
| 404 | Resource not found | Requested wallet does not exist |
| 409 | Conflict | Duplicate wallet for the same currency |
| 503 | Database contention | Configured database wait exceeded |

The service must return safe error responses without exposing implementation details.

---

## 11. Transaction and Database Considerations

Although this document focuses on user and wallet management, some operations involve multiple database records and must be coordinated with the database design.

### 11.1 User registration

User registration inserts a User record containing:

- `user_id`
- `api_key_hash`
- `api_key_salt`

The generated plaintext API key is returned once and is not persisted.

### 11.2 Wallet creation

Wallet creation inserts:

1. A Wallet record.
2. Its corresponding LedgerAccount record.

Both inserts must be part of the same database transaction.

If either insert fails, both must be rolled back.

This maintains the one-to-one relationship between Wallet and LedgerAccount.

### 11.3 Wallet retrieval

Wallet retrieval is a read operation.

It reads the wallet record after authentication and ownership validation.

The returned balance is the stored balance, which must remain consistent with the ledger-derived balance.

### 11.4 Ledger history

Ledger history is retrieved from the ledger entries associated with the wallet's LedgerAccount.

The endpoint performs read-only operations and must not modify ledger records.

### 11.5 Database consistency

The implementation must follow the database decisions in Perla's design:

- SQLite through JDBC.
- WAL mode.
- Foreign-key enforcement.
- Parameterised SQL.
- Integer minor units for monetary values.
- Appropriate primary keys, foreign keys and unique constraints.

The database design is the source of truth for the final SQL schema.

This document does not introduce a second or conflicting schema.

---

## 12. Testing Strategy

The user and wallet components must be tested at the unit and integration levels.

Tests must verify both successful operations and invalid or unauthorized requests.

### 12.1 User registration tests

| Test | Expected result |
|---|---|
| Register a new user | User created successfully |
| Register multiple users | Each receives a unique user ID |
| Verify API key generation | Generated keys are distinct |
| Verify API key storage | Only salted hash and salt are stored |
| Verify API key response | Key is returned in the creation response |
| Inspect subsequent user operations | Original API key is not returned again |
| Simulate database insertion failure | Registration does not report success |
| Inspect application logs | API keys and secrets are not logged |

### 12.2 API authentication tests

| Test | Expected result |
|---|---|
| Send a valid user API key | Request is authenticated |
| Omit the API key | HTTP 401 |
| Send an invalid API key | HTTP 401 |
| Send an incorrect secret portion | HTTP 401 |
| Send a malformed API key | HTTP 401 |
| Use an unknown user identifier in the proposed key format | HTTP 401 |
| Verify another user's key | Authenticates only that user |

Authentication tests must verify that invalid credentials do not expose internal information.

### 12.3 Wallet creation tests

| Test | Expected result |
|---|---|
| Create a wallet for an authenticated user | HTTP 201 |
| Create a wallet for a supported currency | Wallet created |
| Create a wallet for an unsupported currency | HTTP 400 |
| Create a wallet using an invalid user UUID | HTTP 400 |
| Create a wallet without an API key | HTTP 401 |
| Create a wallet with an invalid API key | HTTP 401 |
| Create a wallet for another user | HTTP 403 |
| Create a second wallet for the same currency | HTTP 409 |
| Create wallets for two different supported currencies | Both can be created |
| Verify initial balance | Balance is zero |
| Verify ledger account creation | Exactly one corresponding LedgerAccount exists |

### 12.4 Wallet retrieval tests

| Test | Expected result |
|---|---|
| Retrieve an owned wallet | HTTP 200 |
| Retrieve a wallet belonging to another user | HTTP 403 |
| Retrieve an unknown wallet UUID | HTTP 404 |
| Retrieve using a malformed UUID | HTTP 400 |
| Retrieve without credentials | HTTP 401 |
| Verify returned currency | Matches stored wallet currency |
| Verify returned balance | Matches stored balance |

### 12.5 Ledger-history tests

| Test | Expected result |
|---|---|
| Retrieve history for an owned wallet | HTTP 200 |
| Retrieve history for another user's wallet | HTTP 403 |
| Retrieve history for a nonexistent wallet | HTTP 404 |
| Retrieve history using invalid credentials | HTTP 401 |
| Verify chronological ordering | Entries are returned in chronological order |
| Verify pagination | No page contains more than 100 entries |
| Verify empty history | Empty entry list is returned |
| Verify read-only behaviour | No ledger records are changed |

### 12.6 Integration tests

Integration tests must verify interactions between the controller, service, repository and database layers.

Important scenarios include:

1. Register a user and verify the corresponding database record.
2. Register a user and authenticate using the returned API key.
3. Create a wallet and verify its Wallet record.
4. Verify that the corresponding LedgerAccount is created.
5. Simulate a failure during wallet creation and verify that both inserts roll back.
6. Create two users and verify that one cannot access the other's wallet.
7. Create a wallet and retrieve its balance.
8. Retrieve ledger history after demo funding or a transfer has been completed.
9. Verify that ledger history cannot be modified through the wallet API.

### 12.7 Security and regression tests

Tests must also verify:

- API keys are never stored in plaintext.
- API keys are never included in logs.
- User identity is derived from the authenticated credential.
- A user cannot access another user's wallet by changing the wallet ID.
- A user cannot create a wallet under another user's path.
- SQL parameters are not constructed by concatenating request input.
- Error responses do not reveal secrets or internal database details.

Every defect found during testing must have a regression test, as required by TQ-7.

---

## 13. Open Design Decisions

The following items are not fully specified in SRS v1.3.

They are proposed design decisions and must be reviewed by the team before implementation.

| Reference | Open decision | Proposed approach |
|---|---|---|
| TBD-01 | Supported currencies and minor-unit scales | Maintain a central supported-currency configuration |
| TBD-02 | Final JSON request and response schemas | Agree on field names, formats and error structure across all three designs |
| NFR-6 | API key hashing algorithm and parameters | Select an appropriate secure salted one-way hashing approach |
| TBD-02 / schema coordination | API key lookup mechanism | Consider a public user identifier in the key format so the user record can be located before hash verification |
| TBD-02 | Ledger-history pagination format | Agree on page size, cursor or offset and response metadata |
| TBD-02 | Whether ledger hash fields are public | Return only fields required by the API; do not expose checkpoint secrets |

### 13.1 API key lookup decision

The SRS requires the system to store a salted hash of each user API key and map that key to the created user.

However, a salted hash cannot generally be used as a direct lookup value when the salt is different for every user.

A lookup mechanism is therefore required.

The proposed approach is to include a non-secret public user identifier in the API key and use that identifier to locate the User row. The server then verifies the secret portion using the stored salt and hash.

This approach preserves the existing Appendix B fields:

- `user_id`
- `api_key_hash`
- `api_key_salt`

It does not require silently changing Perla's database schema.

The team must approve the actual key representation and verification algorithm before implementation.

### 13.2 Currency configuration decision

The SRS leaves supported currencies and their minor-unit scales under TBD-01.

The wallet service must therefore use a shared currency configuration rather than hardcoding assumptions into wallet creation.

For each supported currency, the configuration must define its allowed precision.

The final list must be agreed upon by the team.

### 13.3 API contract decision

The examples in this document are proposed API contracts, not final OpenAPI definitions.

The final request and response structures must be reviewed alongside:

- `database-ledger-design.md`
- `transfer-and-concurrency-design.md`
- `docs/architecture.md`

This avoids different endpoints using inconsistent field names or error structures.

---

## 14. Coordination with the Other Design Documents

This design is part of a shared system. Its decisions must remain consistent with the other two technical designs.

### 14.1 Alignment with database and ledger design

The following details must remain consistent with Perla's design:

| Area | Agreed design alignment |
|---|---|
| User entity | `user_id`, `api_key_hash`, `api_key_salt` |
| Wallet entity | `wallet_id`, `user_id`, `currency_code`, `stored_balance_minor` |
| Wallet ownership | `Wallet.user_id` references `User.user_id` |
| Wallet uniqueness | Unique `(user_id, currency_code)` |
| Wallet balance | 64-bit integer minor units |
| Ledger account | One LedgerAccount for each wallet |
| Initial balance | Zero |
| Ledger history | Read entries associated with the wallet's LedgerAccount |
| Database | SQLite through JDBC |
| Financial writes | Transactional and consistent with ledger records |

The final implementation must use the table and column names agreed upon in the shared database design.

### 14.2 Alignment with transfer and concurrency design

The following responsibilities belong to Godha's design:

| Area | Responsibility |
|---|---|
| Transfer authentication | Require a valid `X-User-API-Key` |
| Source ownership | Verify the authenticated user owns the source wallet |
| Destination wallet | Validate that the destination wallet exists and meets transfer rules |
| Idempotency | Handle user-scoped idempotency keys and request fingerprints |
| Concurrency | Obtain the SQLite write lock before checking the balance |
| Atomicity | Commit or roll back all transfer-related changes together |
| Reversal | Permit only the original source-wallet owner to request a full reversal |

This document establishes the authentication and ownership behaviour used by those operations. It does not redefine their transaction processing.

### 14.3 Shared design boundaries

To avoid conflicting implementations:

- `UserService` is responsible for registration-related operations.
- `ApiKeyService` is responsible for API key generation and verification.
- Wallet operations use the shared Wallet and LedgerAccount schema.
- The transfer service must use the same authenticated user identity established by the authentication layer.
- The transfer service must not implement a separate API key verification mechanism.
- Wallet balance updates must be performed only by the appropriate financial services and their database transactions.
- Ledger entries must remain append-only.

The final architecture document should combine these responsibilities into one application architecture.

---

## 15. Requirement Traceability

The following table maps the requirements covered by this design to the relevant design sections and planned tests.

| SRS requirement | Design section | Verification |
|---|---|---|
| FR-1.1 | Section 4 | User registration unit and integration tests |
| FR-1.2 | Section 6 | Wallet ownership, currency and uniqueness tests |
| FR-1.3 | Sections 6.6 and 7 | Initial balance and balance consistency tests |
| FR-1.5 | Section 10.1 | Malformed UUID tests |
| NFR-5 | Sections 10 and 11 | Input validation and parameterised SQL tests |
| NFR-6 | Sections 4 and 5 | API key storage and authentication tests |
| NFR-7 | Sections 5, 8 and 9 | Wallet and ledger ownership tests |
| BR-1 | Section 6 | Duplicate wallet creation tests |
| BR-4 | Section 7.4 | Stored balance versus ledger-derived balance tests |
| FR-4.7 | Section 8 | Chronological ordering and pagination tests |

The repository's main requirements traceability matrix must additionally connect each requirement to its GitHub issue, pull request, automated test and relevant Second Brain note, as required by SE-5.

---

## 16. Conclusion

This design defines how users are registered, how API keys are generated and verified, and how authenticated users create and access their wallets.

It ensures that:

- API keys are returned only once and stored only as salted hashes.
- Authentication establishes the user's identity.
- Authorization prevents access to wallets owned by other users.
- Each user can create only one wallet per supported currency.
- Every wallet starts with a zero balance.
- Every wallet has one corresponding LedgerAccount.
- Monetary values use integer minor units.
- Wallet balances remain consistent with ledger activity.
- Ledger history is accessible only to the wallet owner and is returned chronologically.

The design follows SRS v1.3 and is intended to be reviewed together with the database/ledger and transfer/concurrency designs before implementation begins.

All proposals and unresolved decisions identified in this document require team approval before they become implementation decisions.