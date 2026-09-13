# ADR 0003: REST for decisions, Kafka for propagation

Status: accepted

Use synchronous authenticated REST when the caller needs an immediate financial decision. Use Kafka outboxes to propagate committed facts.

This avoids pretending an asynchronous broker can answer an overdraft decision while still allowing durable read-model convergence and recovery from lost responses.
