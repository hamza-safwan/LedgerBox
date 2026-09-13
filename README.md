# LedgerBank

LedgerBank is a local-first digital banking sandbox for learning how a production-shaped Java payments system behaves. It uses synthetic identities and simulated USD only. It does not move real money and makes no claim of regulatory compliance.

The repository delivers a complete first journey: register, verify through Mailpit, submit deterministic KYC, open an account from an event, issue operator-controlled sandbox funds, transfer between customers, inspect statements, and trace the balanced journal. It also includes an accelerated ACH-style simulator for settlement, rejection, holds, and post-settlement returns.

## System shape

| Boundary | Runtime | Owns |
| --- | --- | --- |
| gateway | Spring Cloud Gateway | Browser edge, JWT validation, origin and CSRF enforcement, correlation IDs, throttling |
| identity-service | Spring Boot, JPA | Users, Argon2 hashes, email actions, RS256 JWTs, refresh-token families |
| customer-service | Spring Boot, JPA | AES-GCM encrypted profiles, KYC workflow, customer outbox |
| ledger-service | Spring Boot, JDBC | Accounts, journals, immutable postings, holds, authoritative balances |
| money-movement-service | Spring Boot, JPA | Idempotent internal transfer lifecycle and uncertain-outcome reconciliation |
| rail-simulator | Spring Boot, JPA | Synthetic external accounts and asynchronous ACH scenarios |
| web | React and TypeScript | Customer and operator experiences using HttpOnly cookie sessions |

Each domain service has its own Maven build, image, database, database credential, and Flyway history. There are no cross-service joins, foreign keys, or shared domain-model jars.

## Prerequisites

- Docker Desktop with the Linux engine running
- Java 17 for host-side tests
- Node 24 and npm for host-side frontend work

Repository wrappers download Maven 3.9.11 on first use. Copy .env.example to .env and replace every development secret before using anything beyond an isolated local machine.

## Start the stack

PowerShell:

    ./scripts/dev-up.ps1

Bash:

    ./scripts/dev-up.sh

Then open:

- Bank application: http://localhost:8080
- Mailpit verification inbox: http://localhost:8025
- Grafana: http://localhost:3001

The development operator is operator@ledgerbank.test with password ChangeMe-Operator-123! unless overridden in .env.

## Demonstration journey

1. Register two synthetic customers and open each verification message in Mailpit.
2. Sign in as each customer, enter synthetic profile data, and choose APPROVE.
3. Account opening occurs asynchronously from customer.kyc-approved.v1; refresh the dashboard after a moment.
4. Sign in as the operator and issue sandbox funding to the first account.
5. Copy the second account number, return to the first customer, and send an internal transfer.
6. Compare both statements and balances. Sign in as the operator to correlate the transfer ID, correlation ID, and journal ID.
7. Link a synthetic external account and run successful, rejected, and returned ACH scenarios.
8. In Operations, inspect generated settlement batches, rerun reconciliation, or pause/advance the persisted rail clock for deterministic failure testing.

All monetary request bodies use signed 64-bit minor units. For example, 2500 means USD 25.00. Floating point values never cross a banking API or enter a ledger table.

## Verification

Run every service test plus the frontend suite:

    ./scripts/test.ps1

or:

    ./scripts/test.sh

Build the frontend:

    cd apps/web
    npm run build

With the Compose stack running, execute the complete two-customer transfer and
operator journal-trace journey:

    cd apps/web
    npx playwright install chromium
    npm run e2e

Validate Compose without starting containers:

    docker compose --env-file .env.example config --quiet

The PostgreSQL invariant suite uses Testcontainers and skips only when Docker is unavailable. See docs/failure-labs.md for concurrency, duplicate, Kafka outage, replay, and timeout-after-commit exercises.

## Documentation

- docs/architecture.md
- docs/domain-glossary.md
- docs/ledger-accounting.md
- docs/event-catalog.md
- docs/security-threat-model.md
- docs/failure-labs.md
- platform/contracts/public-api.yaml

## Safety boundary

Never enter a genuine identity, account number, password used elsewhere, bank credential, or real payment instruction. Development defaults are intentionally obvious and must never be deployed publicly. Only the web container, Mailpit UI, and Grafana bind host ports; services and databases remain on the Compose network.
