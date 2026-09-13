package dev.ledgerbank.rail;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import tools.jackson.databind.ObjectMapper;

@Service
class RailService {
    private final ExternalAccountRepository externals;
    private final AchTransferRepository transfers;
    private final LedgerRailClient ledger;
    private final JdbcTemplate jdbc;
    private final ObjectMapper json;
    private final SandboxClock clock;

    RailService(ExternalAccountRepository externals, AchTransferRepository transfers, LedgerRailClient ledger,
                JdbcTemplate jdbc, ObjectMapper json, SandboxClock clock) {
        this.externals = externals;
        this.transfers = transfers;
        this.ledger = ledger;
        this.jdbc = jdbc;
        this.json = json;
        this.clock = clock;
    }

    @Transactional
    ExternalView link(UUID user, String institution, String last4) {
        ExternalAccount account = externals.save(new ExternalAccount(user, institution, last4, clock.now()));
        return view(account);
    }

    List<ExternalView> externalAccounts(UUID user) {
        return externals.findAllByUserSubjectOrderByCreatedAt(user).stream().map(this::view).toList();
    }

    @Transactional
    AchView create(UUID user, CreateAch request, String key, String correlationId) {
        jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))", Object.class, user + ":" + key);
        ExternalAccount external = externals.findByIdAndUserSubject(request.externalAccountId, user)
                .orElseThrow(() -> new ApiException(404, "external_account_not_found", "External account was not found"));
        if (!external.status.equals("VERIFIED")) throw new ApiException(409, "external_account_unavailable", "External account is unavailable");
        String fingerprint = hash(request.ledgerAccountId + "|" + request.externalAccountId + "|" + request.direction + "|" + request.amountMinor + "|" + request.scenario);
        Optional<AchTransfer> existing = transfers.findByUserSubjectAndIdempotencyKey(user, key);
        if (existing.isPresent()) {
            if (!existing.get().fingerprint.equals(fingerprint)) throw new ApiException(409, "idempotency_conflict", "Idempotency key was used with different content");
            return view(existing.get());
        }
        String correlation = correlationId == null ? UUID.randomUUID().toString() : correlationId;
        Instant now = clock.now();
        AchTransfer transfer = transfers.saveAndFlush(new AchTransfer(user, request.ledgerAccountId, request.externalAccountId,
                request.direction, request.amountMinor, request.scenario, key, fingerprint, correlation, now));
        timeline(transfer, transfer.status, "ACH request accepted");
        publish(transfer);
        return view(transfer);
    }

    AchView get(UUID user, UUID id, boolean operator) {
        AchTransfer transfer = operator ? transfers.findById(id).orElseThrow(this::notFound)
                : transfers.findByIdAndUserSubject(id, user).orElseThrow(this::notFound);
        return view(transfer);
    }

    List<AchView> list(UUID user) { return transfers.findAllByUserSubjectOrderByCreatedAtDesc(user).stream().map(this::view).toList(); }
    List<AchView> ops() { return transfers.findAll().stream().sorted(Comparator.comparing((AchTransfer transfer) -> transfer.createdAt).reversed()).map(this::view).toList(); }
    List<Timeline> timeline(UUID id) { return jdbc.query("select stage,detail,occurred_at from rail_timeline where transfer_id=? order by occurred_at,id", (row, number) -> new Timeline(row.getString(1), row.getString(2), row.getTimestamp(3).toInstant()), id); }

    List<SettlementBatchView> batches() {
        return jdbc.query("select id,status,entry_count,total_deposit_minor,total_withdrawal_minor,opened_at,closed_at,reconciled_at,exception_detail from settlement_batches order by opened_at desc limit 100", this::batchView);
    }

    @Transactional
    SettlementBatchView reconcileBatch(UUID id) {
        SettlementBatchView batch = batch(id);
        if (batch.status.equals("OPEN")) throw new ApiException(409, "batch_open", "Settlement batch is still open");
        reconcile(id);
        return batch(id);
    }

    @Transactional
    void advanceDue() {
        Instant now = clock.now();
        List<AchTransfer> due = transfers.findTop50ByNextTransitionAtBeforeAndStatusInOrderByNextTransitionAt(now,
                List.of("PROCESSING", "INITIATED", "SUBMITTED", "SETTLED"));
        UUID batchId = due.stream().anyMatch(transfer -> transfer.status.equals("INITIATED")) ? openBatch(now) : null;
        for (AchTransfer transfer : due) {
            try {
                advance(transfer, batchId);
            } catch (ResourceAccessException | HttpServerErrorException unavailable) {
                Instant retryAt = clock.now();
                transfer.failureDetail = "Ledger temporarily unavailable; retry scheduled";
                transfer.nextTransitionAt = retryAt.plusSeconds(3);
                transfer.updatedAt = retryAt;
            } catch (HttpClientErrorException rejected) {
                if (transfer.direction.equals("WITHDRAWAL") && Set.of("PROCESSING", "INITIATED", "SUBMITTED").contains(transfer.status)) {
                    try { ledger.release(transfer.id, transfer.correlationId); } catch (Exception ignored) { }
                }
                transfer.status = "REJECTED";
                transfer.failureDetail = "LEDGER_REJECTED_" + rejected.getStatusCode().value();
                transfer.updatedAt = clock.now();
                timeline(transfer, "REJECTED", transfer.failureDetail);
                publish(transfer);
            }
        }
        transfers.flush();
        if (batchId != null) closeBatch(batchId, clock.now());
        jdbc.query("select id from settlement_batches where status='CLOSED' order by opened_at limit 50",
                (row, number) -> row.getObject(1, UUID.class)).forEach(this::reconcile);
    }

    private void advance(AchTransfer transfer, UUID batchId) {
        if (transfer.status.equals("PROCESSING")) {
            ledger.hold(transfer.id, transfer.ledgerAccountId, transfer.amountMinor, transfer.userSubject, transfer.correlationId);
            move(transfer, "INITIATED", "Outgoing funds reserved", 2);
            return;
        }
        if (transfer.status.equals("INITIATED")) {
            recordBatch(batchId, transfer);
            move(transfer, "SUBMITTED", "Submitted in settlement batch " + batchId, 3);
            return;
        }
        if (transfer.status.equals("SUBMITTED")) {
            if (transfer.scenario.equals("REJECT")) {
                if (transfer.direction.equals("WITHDRAWAL")) ledger.release(transfer.id, transfer.correlationId);
                move(transfer, "REJECTED", "Simulated receiving institution rejected the entry", 315_360_000);
                return;
            }
            LedgerRailClient.Journal journal = transfer.direction.equals("DEPOSIT")
                    ? ledger.deposit(transfer.id, transfer.ledgerAccountId, transfer.amountMinor, transfer.correlationId)
                    : ledger.capture(transfer.id, transfer.correlationId);
            transfer.journalId = journal.journalId();
            move(transfer, "SETTLED", "Settlement journal posted", transfer.scenario.equals("RETURN") ? 5 : 315_360_000);
            return;
        }
        if (transfer.status.equals("SETTLED") && transfer.scenario.equals("RETURN")) {
            String original = transfer.direction.equals("DEPOSIT") ? "deposit:" + transfer.id : "withdrawal:hold:" + transfer.id;
            LedgerRailClient.Journal journal = ledger.reverse(original, transfer.id, transfer.correlationId);
            transfer.journalId = journal.journalId();
            move(transfer, "RETURNED", "Post-settlement return reversed the original journal", 315_360_000);
        }
    }

    private UUID openBatch(Instant now) {
        UUID id = UUID.randomUUID();
        jdbc.update("insert into settlement_batches(id,status,opened_at) values (?,'OPEN',?)", id, Timestamp.from(now));
        return id;
    }

    private void recordBatch(UUID batchId, AchTransfer transfer) {
        if (batchId == null) throw new IllegalStateException("Submitted transfer requires a settlement batch");
        transfer.settlementBatchId = batchId;
        jdbc.update("insert into settlement_batch_entries(batch_id,ach_transfer_id,direction,amount_minor) values (?,?,?,?) on conflict(ach_transfer_id) do nothing",
                batchId, transfer.id, transfer.direction, transfer.amountMinor);
    }

    private void closeBatch(UUID id, Instant now) {
        jdbc.update("update settlement_batches b set status='CLOSED',entry_count=(select count(*) from settlement_batch_entries e where e.batch_id=b.id),total_deposit_minor=(select coalesce(sum(amount_minor),0) from settlement_batch_entries e where e.batch_id=b.id and direction='DEPOSIT'),total_withdrawal_minor=(select coalesce(sum(amount_minor),0) from settlement_batch_entries e where e.batch_id=b.id and direction='WITHDRAWAL'),closed_at=? where b.id=?",
                Timestamp.from(now), id);
    }

    private void reconcile(UUID id) {
        Integer inFlight = jdbc.queryForObject("select count(*) from settlement_batch_entries e join ach_transfers t on t.id=e.ach_transfer_id where e.batch_id=? and t.status in ('PROCESSING','INITIATED','SUBMITTED')", Integer.class, id);
        if (inFlight != null && inFlight > 0) return;
        Integer mismatches = jdbc.queryForObject("select count(*) from settlement_batch_entries e join ach_transfers t on t.id=e.ach_transfer_id where e.batch_id=? and ((t.status in ('SETTLED','RETURNED') and t.journal_id is null) or (t.status='REJECTED' and t.journal_id is not null))", Integer.class, id);
        Instant now = clock.now();
        if (mismatches != null && mismatches > 0) {
            jdbc.update("update settlement_batches set status='EXCEPTION',reconciled_at=?,exception_detail=? where id=?",
                    Timestamp.from(now), mismatches + " rail entries did not match their ledger outcome", id);
        } else {
            jdbc.update("update settlement_batches set status='RECONCILED',reconciled_at=?,exception_detail=null where id=?",
                    Timestamp.from(now), id);
        }
    }

    private void move(AchTransfer transfer, String status, String detail, long nextSeconds) {
        AchLifecycle.requireLegal(transfer.status, status, transfer.scenario);
        Instant now = clock.now();
        transfer.status = status;
        transfer.failureDetail = null;
        transfer.updatedAt = now;
        transfer.nextTransitionAt = now.plusSeconds(nextSeconds);
        timeline(transfer, status, detail);
        publish(transfer);
    }

    private void timeline(AchTransfer transfer, String stage, String detail) {
        jdbc.update("insert into rail_timeline(transfer_id,stage,detail,occurred_at) values (?,?,?,?)",
                transfer.id, stage, detail, Timestamp.from(clock.now()));
    }

    private void publish(AchTransfer transfer) {
        try {
            UUID eventId = UUID.randomUUID();
            Instant now = clock.now();
            Map<String, Object> envelope = Map.of("eventId", eventId, "eventType", "rail.ach-status-changed.v1", "eventVersion", 1,
                    "aggregateId", transfer.id, "occurredAt", now, "correlationId", transfer.correlationId,
                    "causationId", transfer.id.toString(), "payload", Map.of("achTransferId", transfer.id, "status", transfer.status,
                            "direction", transfer.direction, "amountMinor", transfer.amountMinor, "currency", "USD"));
            jdbc.update("insert into outbox_events(id,aggregate_id,event_type,payload,occurred_at) values (?,?,?,cast(? as jsonb),?)",
                    eventId, transfer.id, "rail.ach-status-changed.v1", json.writeValueAsString(envelope), Timestamp.from(now));
        } catch (Exception exception) {
            throw new IllegalStateException(exception);
        }
    }

    private String hash(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception exception) { throw new IllegalStateException(exception); }
    }

    private SettlementBatchView batch(UUID id) {
        return jdbc.query("select id,status,entry_count,total_deposit_minor,total_withdrawal_minor,opened_at,closed_at,reconciled_at,exception_detail from settlement_batches where id=?",
                this::batchView, id).stream().findFirst().orElseThrow(() -> new ApiException(404, "batch_not_found", "Settlement batch was not found"));
    }

    private SettlementBatchView batchView(java.sql.ResultSet row, int number) throws java.sql.SQLException {
        Timestamp closed = row.getTimestamp(7);
        Timestamp reconciled = row.getTimestamp(8);
        return new SettlementBatchView(row.getObject(1, UUID.class), row.getString(2), row.getInt(3), row.getLong(4), row.getLong(5),
                row.getTimestamp(6).toInstant(), closed == null ? null : closed.toInstant(), reconciled == null ? null : reconciled.toInstant(), row.getString(9));
    }

    private ApiException notFound() { return new ApiException(404, "ach_transfer_not_found", "ACH transfer was not found"); }
    private ExternalView view(ExternalAccount account) { return new ExternalView(account.id, account.institutionName, account.maskedNumber, account.status, account.createdAt); }
    private AchView view(AchTransfer transfer) { return new AchView(transfer.id, transfer.ledgerAccountId, transfer.externalAccountId, transfer.direction,
            transfer.amountMinor, transfer.currency, transfer.scenario, transfer.status, transfer.journalId, transfer.settlementBatchId,
            transfer.failureDetail, transfer.correlationId, transfer.createdAt, transfer.updatedAt); }

    record CreateAch(UUID ledgerAccountId, UUID externalAccountId, String direction, long amountMinor, String scenario) { }
    record ExternalView(UUID externalAccountId, String institutionName, String maskedNumber, String status, Instant createdAt) { }
    record AchView(UUID achTransferId, UUID ledgerAccountId, UUID externalAccountId, String direction, long amountMinor, String currency,
                   String scenario, String status, UUID journalId, UUID settlementBatchId, String failureDetail, String correlationId,
                   Instant createdAt, Instant updatedAt) { }
    record Timeline(String stage, String detail, Instant occurredAt) { }
    record SettlementBatchView(UUID batchId, String status, int entryCount, long totalDepositMinor, long totalWithdrawalMinor,
                               Instant openedAt, Instant closedAt, Instant reconciledAt, String exceptionDetail) { }
}
