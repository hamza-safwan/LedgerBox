package dev.ledgerbank.movement;
import jakarta.persistence.*;import java.time.Instant;import java.util.UUID;
@Entity @Table(name="transfers",uniqueConstraints=@UniqueConstraint(columnNames={"user_subject","idempotency_key"}))
class Transfer{
 @Id UUID id;@Column(name="user_subject")UUID userSubject;@Column(name="source_account_id")UUID sourceAccountId;@Column(name="destination_account_number")String destinationAccountNumber;@Column(name="amount_minor")long amountMinor;@Column(length=3)String currency;String status;@Column(name="failure_code")String failureCode;@Column(name="journal_id")UUID journalId;@Column(name="idempotency_key")String idempotencyKey;@Column(name="request_fingerprint",length=64)String requestFingerprint;@Column(name="correlation_id")String correlationId;@Column(name="created_at")Instant createdAt;@Column(name="updated_at")Instant updatedAt;@Version long version;
 protected Transfer(){} Transfer(UUID user,UUID source,String destination,long amount,String key,String fingerprint,String correlation){id=UUID.randomUUID();userSubject=user;sourceAccountId=source;destinationAccountNumber=destination;amountMinor=amount;currency="USD";status="RECEIVED";idempotencyKey=key;requestFingerprint=fingerprint;correlationId=correlation;createdAt=updatedAt=Instant.now();}
}
