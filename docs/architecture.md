# Architecture

LedgerBank separates workflow state from financial truth. Identity knows who may act, customer owns synthetic onboarding, money movement owns a transfer state machine, and only the ledger decides whether money posted.

    Browser
       |
       v
    web/nginx --> gateway
                    |-- identity-service --> identity_db --> Mailpit
                    |-- customer-service --> customer_db --> customer.events
                    |-- ledger-service   --> ledger_db   --> ledger.events
                    |-- movement-service --> movement_db
                    |-- rail-simulator   --> rail_db
                                               |
                    Kafka <---- outbox workers |

Immediate decisions use REST. A customer transfer calls the ledger synchronously because the customer needs an acceptance, rejection, or explicit PROCESSING response. State propagation uses Kafka outboxes. Every service writes only its own database.

## Trust boundaries

The browser sees only nginx and the gateway. The gateway converts the HttpOnly access cookie into an Authorization header after stripping any browser-supplied Authorization value. Internal service and database ports are not published.

The identity service signs short-lived RS256 tokens. Browser tokens target the ledgerbank-api audience. Service tokens target ledgerbank-internal and carry ledger.post and ledger.read scopes. The ledger protects every internal endpoint with ledger.post.

## Transfer sequence

1. The gateway verifies origin, CSRF token, role, and JWT and establishes a correlation ID.
2. Money movement stores RECEIVED plus an outbox event under the caller's idempotency key.
3. It transitions to PROCESSING and sends a stable command ID to the ledger.
4. The ledger takes an advisory lock on the business reference, locks accounts in UUID order, checks ownership, status, currency, and funds, then inserts one journal and two postings.
5. The deferred PostgreSQL constraint checks equality of debit and credit totals at commit.
6. The ledger updates balance projections and its outbox in that same transaction.
7. Money movement marks POSTED from the REST reply, the ledger event, or reconciliation. All three paths are idempotent.

If the reply is lost after ledger commit, the transfer remains PROCESSING. Reconciliation first searches business reference transfer:{transferId}. If absent, it safely resends the original stable command. The advisory lock and unique business reference prevent a second journal.

## Data ownership

| Database | Allowed owner | Important records |
| --- | --- | --- |
| identity_db | identity-service | user accounts, one-time actions, refresh hashes, auth audit |
| customer_db | customer-service | encrypted profiles, KYC status, outbox |
| ledger_db | ledger-service | accounts, journals, postings, holds, inbox/outbox, audit |
| movement_db | money-movement-service | transfer aggregate, timeline, inbox/outbox |
| rail_db | rail-simulator | external accounts, ACH aggregate, timeline, outbox |

Cross-service IDs are opaque UUID references, not database constraints. Services learn facts from authenticated commands or versioned events.

## Observability

All services expose health and Prometheus metrics only inside the network. Prometheus scrapes them every ten seconds. Micrometer tracing exports OTLP spans through the collector to Tempo. JSON console logs carry trace context and the gateway returns X-Correlation-ID to the caller. Grafana is provisioned with Prometheus and Tempo data sources.

## Deliberate local constraints

Compose DNS is discovery. There is no Kubernetes, public hosting, or managed secret store. Kafka uses one KRaft broker, so availability differs from a real multi-broker installation. PostgreSQL is one server but still enforces a separate database and login per service.
