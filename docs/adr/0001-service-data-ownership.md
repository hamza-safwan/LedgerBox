# ADR 0001: Database per service

Status: accepted

Each service owns a PostgreSQL database and credential. Cross-service joins, direct reads, and foreign keys are forbidden.

This makes ownership and failure boundaries visible, keeps migrations independently deployable, and forces explicit contracts. It costs local resources and introduces eventual consistency, accepted because those are central learning goals.
