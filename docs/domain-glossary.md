# Domain glossary

Account: A ledger address that receives postings. Customer checking accounts are liabilities; settlement cash is an asset.

Available balance: Posted balance minus active reservations. It is the amount currently eligible to spend.

Business reference: A stable semantic key for a financial command. It is unique in the ledger and makes command replay safe.

Command ID: A caller-generated UUID that stays the same across retries.

Correlation ID: A request-wide trace value propagated through HTTP, audit records, events, and responses.

Causation ID: The command or aggregate identifier that caused an event.

Credit and debit: The two directions of double-entry accounting. They are not synonyms for positive and negative.

Deposit liability: Money the bank owes a customer. Credits increase it; debits reduce it.

Hold: A reservation that reduces available balance without changing posted balance.

Inbox: A consumer table keyed by event ID, used to discard duplicate deliveries.

Journal: An immutable business event containing balanced postings.

KYC: Know Your Customer. Here it is only a deterministic simulation using APPROVE, REJECT, or REVIEW test codes.

Outbox: Events committed in the same database transaction as domain state and later acknowledged to Kafka.

Posting: One immutable debit or credit line within a journal.

Projection: A transactionally maintained balance column used for decisions. It can be recomputed from postings.

Rail: An external movement network. LedgerBank simulates ACH timing and outcomes without contacting a provider.

Reconciliation: Determining an uncertain command outcome from the authoritative ledger and safely replaying a missing stable command.

Return: A post-settlement event represented as a linked reversal journal.

Settlement: The point at which a simulated rail movement creates a posted journal.
