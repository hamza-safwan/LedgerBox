# Security threat model

This model protects a local educational sandbox. It does not assert compliance with banking, privacy, KYC, AML, or payment-network regulation.

## Protected assets

- Password hashes and refresh-token hashes
- Synthetic profile fields and their encryption key
- JWT signing material and service credential
- Ledger posting integrity and balance availability
- Operator actions and correlation/audit trails

## Principal threats and controls

| Threat | Control |
| --- | --- |
| Credential database theft | Argon2id hashes; generic login errors |
| Password guessing | Gateway per-IP throttle plus identity lockout |
| Stolen access token | Ten-minute expiry, HttpOnly SameSite cookie |
| Stolen refresh token | Opaque hashed token, rotation, family revocation on reuse |
| Cross-site request forgery | Same-origin boundary, strict Origin allowlist, synchronizer header/cookie equality |
| Browser Authorization spoofing | Gateway removes the incoming header and derives it from LB_ACCESS |
| Customer reads another account | Ledger ownership predicate and source-owner check |
| Customer invokes operations | Gateway and each owning service require OPERATOR |
| Service confused-deputy access | Five-minute service JWT, internal audience, narrow ledger scope |
| Duplicate financial command | Idempotency fingerprint, advisory lock, unique business reference |
| Concurrent overspend | Ordered SELECT FOR UPDATE and available-balance check |
| Ledger tampering | Deferred balance constraint and immutable posting trigger |
| PII disclosure | AES-GCM at rest; no PII in events; synthetic-data warning |
| Lost Kafka message | Transactional outbox and publish acknowledgement |
| Duplicate Kafka message | Consumer inbox keyed by event ID |
| Lost REST reply after commit | PROCESSING state, event confirmation, reference reconciliation, safe replay |

## Known local limitations

Development defaults are present for convenience. Cookie Secure is false only in the local profile. The Compose broker is not encrypted because it has no host exposure. Production would require a secret manager, TLS and mTLS, persistent managed signing keys, network policy, centralized audit retention, WAF-grade throttling, and independent security review.

Do not put PII, passwords, cookies, JWTs, reset tokens, verification tokens, encryption keys, or service secrets in logs or Kafka payloads. Correlation IDs and opaque UUIDs are safe trace handles.
