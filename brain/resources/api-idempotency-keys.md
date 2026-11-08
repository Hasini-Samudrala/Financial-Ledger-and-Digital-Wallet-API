---
date: 2026-11-07
tags:
  - financial-ledger
  - idempotency
  - api-design
  - transfers
  - stripe
source: "https://docs.stripe.com/api/idempotent_requests (Stripe API Reference, Idempotent requests)"
---

# API Idempotency Keys

## Summary
An API request is idempotent when sending it more than once has the same effect as sending it once. Stripe's documentation says this lets you safely retry a request after a connection error without performing the same operation twice. The client does this by attaching a unique idempotency key, which the server uses to recognize a retry.

*Labels used below: **Stripe docs** means documented Stripe behavior. **Ours** means our own example or project decision.*

## Key Takeaways
- **The problem is not knowing what happened (Stripe docs).** If a connection error occurs, the client cannot tell whether the server processed the request. Retrying without protection risks creating a second object or applying an update twice.
- **The client creates the key (Stripe docs).** The key is sent with the request and is unique to that operation. Stripe suggests random V4 UUIDs, allows up to 255 characters, and says not to use sensitive data such as email addresses. Stripe's example sends it in an `Idempotency-Key` header.
- **Same key, same request: return the saved result (Stripe docs).** In Stripe's API v1, Stripe saves the status code and body of the first request that used a key and returns that saved response to retries, even if the first response was a `500` error. Its newer API v2 works differently: it does not repeat successful operations and finishes failed or partly failed work where possible.
- **Same key, different request: error (Stripe docs).** Stripe compares the incoming parameters with those of the original request and returns an error if they differ, to prevent accidental misuse.
- **Some details are Stripe-specific (Stripe docs).** In v1, keys can be removed after at least 24 hours, and reusing a removed key creates a new request. Results are saved only once execution starts, so a request that fails validation or conflicts with one running at the same moment is not saved and can be retried. `GET` and `DELETE` requests do not need keys because they are idempotent by definition.

## Example: Alice's lost response
*This example is ours. It applies Stripe's documented pattern to our transfer.*

1. Alice's app creates a key, `K1`, and sends "transfer ₹500 to Bob" with `K1`.
2. The server has not seen `K1` before, so it processes the transfer and saves the result under `K1`.
3. The response is lost on the network, so Alice's app does not know the transfer happened.
4. Alice's app retries with the same `K1` and the same request.
5. The server finds `K1`, sees that the request matches the original, and returns the saved result. **No second ₹500 moves.**

Without a key, step 4 would look like a brand-new request, and Alice would pay ₹1,000. If the retry reused `K1` with a different amount (say ₹700), the server should refuse it, because a key identifies one specific request.

## Relevance to Financial Ledger
- **FR-2 (Idempotent Transfers):** The project statement requires an idempotency key, sent as a header, on every transfer so retries never double-charge. Stripe's documented pattern matches this: a client-generated key, saved results for repeats, and an error for a changed request.
- **Why this is not covered by the ledger:** A duplicate transfer would create a second debit and credit that are balanced, so the ledger would still add up and reconciliation (FR-5) would not flag it. Alice would still be charged twice. Idempotency answers a different question from double-entry accounting (is each entry balanced?) and from database transactions (does one attempt finish completely or not at all?): it decides whether the same request should be processed at all a second time.
- **Effect on the transfer API (ours):** The endpoint rejects a request with no key. It then looks up the key before doing anything. If the key is new, it runs the transfer and stores the key, a fingerprint of the request, and the result. If the key exists with the same request, it returns the stored result. If the key exists with a different request, it returns an error.
- **Simultaneous retries (ours):** Two copies of the same request can arrive at the same moment, so the key must be stored in a way that only one of them can create (for example, a unique constraint on the key).

## Additional Interpretation
*Ours, not from Stripe's docs.*
- **We do not have to copy Stripe's retention rule.** Stripe v1 may remove keys after 24 hours, which means a very late retry would be treated as a new transfer. For money movement, we should choose our own retention period and keep keys at least as long as a client could plausibly retry.
- **Decide which outcomes get saved.** Stripe v1 saves the response once execution starts, even if it is an error, but not validation failures. We need our own rule, for example that a transfer rolled back without effect can be retried with the same key.

## Source Limitations
The page is a short reference. It does not say what error a mismatched request returns or how Stripe stores keys. It also describes different behavior for API v1 and API v2, so "Stripe's behavior" is not a single rule we can copy.

## Related Notes
- [[sqlite-acid-transactions]]
- [[double-entry-accounting]]