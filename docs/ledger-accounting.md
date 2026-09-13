# Financial accounting guide

LedgerBank records USD as positive bigint minor units on postings. Direction carries debit or credit; balance projections are signed bigint columns. A request amount must be positive and never uses binary floating point.

## Chart of accounts

The seeded Sandbox settlement cash account is an asset owned by the simulated bank. Every customer checking account is a deposit liability: the balance is money the simulated bank owes that customer.

| Movement | Debit | Credit | Customer effect |
| --- | --- | --- | --- |
| Sandbox funding | settlement cash asset | customer deposit liability | balance increases |
| Internal transfer | sender deposit liability | recipient deposit liability | sender down, recipient up |
| ACH deposit | settlement cash asset | customer deposit liability | balance increases |
| ACH withdrawal capture | customer deposit liability | settlement cash asset | balance decreases |
| Return | opposite of original postings | opposite of original postings | original effect is reversed |

For assets, debit increases and credit decreases the projected balance. For liabilities, credit increases and debit decreases it.

## Invariants

- Every journal has a unique business reference.
- Every posting amount is positive and USD.
- At commit, total debits must equal total credits and must be non-zero.
- Posted rows cannot be updated or deleted; corrections use a linked reversal journal.
- Posted and available projections update in the journal transaction.
- Customer debits check available funds after deterministic row locking.
- Holds reduce available balance without changing posted balance.

The balance trigger is DEFERRABLE INITIALLY DEFERRED so a transaction may insert both sides separately but cannot commit an incomplete journal.

## Concurrency and idempotency

Account UUIDs are sorted before SELECT FOR UPDATE. Competing transfers therefore acquire locks in the same order, preventing common transfer deadlocks and ensuring only the first request that has sufficient available funds succeeds.

The business reference is derived from the stable command: funding:{accountId}:{key}, transfer:{commandId}, deposit:{commandId}, withdrawal:{holdReference}, or reversal:{commandId}. An advisory transaction lock serializes requests for that reference; a retry returns the existing journal.

## Holds

An outgoing ACH request creates hold:{achTransferId}. Creating the hold subtracts available funds. Capture posts the withdrawal and resolves the hold, compensating the projection so the amount is not subtracted twice. Rejection releases the hold. Expiration is represented in the schema for a later expiry worker.

## Statement semantics

Statements are read from immutable postings joined to journals, never from transfer-service caches. Liability credits appear positive and debits negative to a customer. The cursor is the booked timestamp of the last returned posting; page size is capped at 100.
