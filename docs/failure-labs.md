# Failure labs

Run these only against the local synthetic stack. Keep one terminal on:

    docker compose logs -f gateway money-movement-service ledger-service

Use Grafana and the operator transfer trace to follow the correlation ID.

## Duplicate request

1. In browser developer tools, preserve a successful POST /api/v1/transfers request.
2. Replay it with the same Idempotency-Key and identical JSON. The same transfer and journal IDs return.
3. Replay the key with a different amount. The API returns 409 idempotency_conflict.
4. Query the operator trace and verify one transfer and one business reference.

## Concurrent spending

1. Fund one account with exactly USD 100.00.
2. Prepare two requests for USD 80.00 to different destination accounts.
3. Send them concurrently with distinct idempotency keys.
4. One may post; the other must return insufficient_funds. The source available balance never becomes negative.
5. Inspect PostgreSQL lock timing or trace spans to see deterministic account-ID lock ordering.

## Kafka outage

1. Stop Kafka:

       docker compose stop kafka

2. Complete a KYC approval or post a transfer. Domain state and its outbox row commit even though publication cannot.
3. Start Kafka:

       docker compose start kafka

4. The outbox worker publishes and fills published_at after acknowledgement. The account opens or transfer read model converges without a duplicate posting.

## Consumer replay

1. Stop money-movement-service after a ledger journal posts.
2. Reset or recreate its local Kafka consumer group only in this disposable environment.
3. Start the service. The inbox_events primary key accepts an event ID once; transfer state remains POSTED and no new journal is created.

## Poison event and redrive

1. Publish malformed JSON to customer.events or ledger.events using the Kafka CLI in the broker container.
2. Observe three retries followed by customer.events.dlt or ledger.events.dlt.
3. Sign in as the operator and list /api/v1/ops/ledger-dead-letters or /api/v1/ops/movement-dead-letters.
4. Correct the producer defect before redrive. POST to /{deadLetterId}/redrive with an Idempotency-Key.
5. Repeating the same key returns the original redrive result; changing the key returns 409.

## Timeout after ledger commit

1. Add a breakpoint or temporary network interruption after ledger commit but before money movement receives the HTTP response.
2. The client sees PROCESSING and the ledger contains transfer:{transferId}.
3. Restore connectivity. Either ledger.journal-posted.v1 or the five-second reconciliation worker sets POSTED.
4. If the first command never reached the ledger, reconciliation gets 404 and safely resends the same transfer ID.
5. Confirm one journal exists.

## Service restart

During any scenario, run:

    docker compose restart money-movement-service

or restart identity-service, ledger-service, Kafka, or rail-simulator. Database state, outboxes, inboxes, stable command IDs, unique references, and the identity signing-key volume preserve money correctness and valid sessions.
