# ADR 0002: Explicit JDBC for the ledger

Status: accepted

The ledger uses Spring JDBC and explicit SQL while workflow services use JPA.

Posting correctness depends on exact lock ordering, transaction boundaries, deferred constraints, immutable triggers, and predictable statements. Explicit SQL makes those decisions reviewable. JPA remains appropriate for identity and lifecycle aggregates.
