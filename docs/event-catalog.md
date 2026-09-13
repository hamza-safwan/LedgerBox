# Event catalog

Kafka delivery is at least once. Aggregate ID is the Kafka key so events for one aggregate retain partition order. Consumers must accept duplicates and reject or dead-letter malformed payloads.

Every envelope has eventId, eventType, eventVersion, aggregateId, occurredAt, correlationId, causationId, and payload. Times are UTC ISO-8601. Payloads contain opaque IDs and financial metadata, never profile data, credentials, cookies, or tokens.

| Topic | Event type | Producer | Consumer | Purpose |
| --- | --- | --- | --- | --- |
| customer.events | customer.kyc-approved.v1 | customer-service | ledger-service | Open one USD account after approval |
| ledger.events | account.opened.v1 | ledger-service | read-model consumers | Announce account creation |
| ledger.events | ledger.journal-posted.v1 | ledger-service | money-movement-service | Resolve PROCESSING transfers |
| movement.events | transfer.received.v1 | money-movement-service | operations/read models | Record accepted intent |
| movement.events | transfer.posted.v1 | money-movement-service | operations/read models | Record final success |
| movement.events | transfer.failed.v1 | money-movement-service | operations/read models | Record final rejection |
| rail.events | rail.ach-status-changed.v1 | rail-simulator | operations/read models | Observe asynchronous rail lifecycle |

Poison customer events retry three times and then publish to customer.events.dlt. Poison ledger events do the same to ledger.events.dlt. Ledger and money movement index their respective dead letters in private tables. Operator endpoints list them and redrive a selected record with an Idempotency-Key.

## Compatibility rules

- Additive optional payload fields are backward compatible within an event version.
- Renaming, removing, changing meaning, or making a field required creates a new event type version.
- Consumers ignore unknown event types and fields.
- A redrive preserves the original event ID and payload so inbox deduplication remains effective.
- Published-at is recorded only after Kafka acknowledges the send.
