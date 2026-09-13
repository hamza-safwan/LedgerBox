# ADR 0004: Signed 64-bit minor units

Status: accepted

All amounts are signed 64-bit integer minor units paired with ISO currency. Version one accepts USD only.

Floating point is forbidden because binary representation and implicit rounding are unsuitable for ledger invariants. Positive posting amounts plus explicit directions keep accounting intent clear.
