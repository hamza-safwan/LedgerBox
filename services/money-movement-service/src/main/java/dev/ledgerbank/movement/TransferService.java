package dev.ledgerbank.movement;

import tools.jackson.databind.ObjectMapper;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.web.client.*;

@Service
class TransferService {
    private final TransferRepository transfers;private final LedgerClient ledger;private final JdbcTemplate jdbc;private final ObjectMapper json;private final TransactionTemplate tx;
    TransferService(TransferRepository transfers,LedgerClient ledger,JdbcTemplate jdbc,ObjectMapper json,PlatformTransactionManager transactionManager){this.transfers=transfers;this.ledger=ledger;this.jdbc=jdbc;this.json=json;this.tx=new TransactionTemplate(transactionManager);}

    TransferView submit(UUID user,CreateTransfer request,String key,String correlationId){
        String fingerprint=hash(request.sourceAccountId()+"|"+request.destinationAccountNumber()+"|"+request.amountMinor()+"|USD");String cid=correlationId==null?UUID.randomUUID().toString():correlationId;
        Transfer transfer;
        try{transfer=Objects.requireNonNull(tx.execute(s->{Optional<Transfer>prior=transfers.findByUserSubjectAndIdempotencyKey(user,key);if(prior.isPresent()){assertFingerprint(prior.get(),fingerprint);return prior.get();}Transfer created=transfers.saveAndFlush(new Transfer(user,request.sourceAccountId(),request.destinationAccountNumber(),request.amountMinor(),key,fingerprint,cid));timeline(created,"RECEIVED","Transfer request accepted");publish(created,"transfer.received.v1");return created;}));}
        catch(DataIntegrityViolationException race){Optional<Transfer> prior=transfers.findByUserSubjectAndIdempotencyKey(user,key);if(prior.isEmpty())throw race;transfer=prior.get();assertFingerprint(transfer,fingerprint);}
        if(!transfer.status.equals("RECEIVED"))return view(transfer);
        UUID id=transfer.id;
        tx.executeWithoutResult(s->{Transfer current=transfers.findById(id).orElseThrow();current.status="PROCESSING";current.updatedAt=Instant.now();timeline(current,"PROCESSING","Ledger command dispatched");});
        try{LedgerClient.Journal journal=ledger.post(transfer.id,transfer.sourceAccountId,transfer.destinationAccountNumber,transfer.amountMinor,transfer.userSubject,transfer.correlationId);markPosted(transfer.id,journal.journalId(),"Ledger confirmed the balanced journal");}
        catch(RestClientResponseException e){if(e.getStatusCode().is4xxClientError())markFailed(transfer.id,"LEDGER_REJECTED_"+e.getStatusCode().value());}
        catch(ResourceAccessException uncertain){timelineOutside(transfer.id,"OUTCOME_UNCERTAIN","Ledger did not answer; reconciliation will determine the result");}
        return view(transfers.findById(transfer.id).orElseThrow());
    }

    TransferView get(UUID id,UUID user,boolean operator){Transfer t=operator?transfers.findById(id).orElseThrow(()->new ApiException(404,"transfer_not_found","Transfer was not found")):transfers.findByIdAndUserSubject(id,user).orElseThrow(()->new ApiException(404,"transfer_not_found","Transfer was not found"));return view(t);}
    List<TransferView> list(UUID user){return transfers.findAllByUserSubjectOrderByCreatedAtDesc(user).stream().map(this::view).toList();}
    List<TransferView> opsList(){return transfers.findAll().stream().sorted(Comparator.comparing((Transfer t)->t.createdAt).reversed()).limit(100).map(this::view).toList();}
    List<TimelineView> timeline(UUID id){if(!transfers.existsById(id))throw new ApiException(404,"transfer_not_found","Transfer was not found");return jdbc.query("select stage,detail,correlation_id,occurred_at from transfer_timeline where transfer_id=? order by occurred_at,id",(r,n)->new TimelineView(r.getString(1),r.getString(2),r.getString(3),r.getTimestamp(4).toInstant()),id);}
    List<EventView> events(UUID id){if(!transfers.existsById(id))throw new ApiException(404,"transfer_not_found","Transfer was not found");return jdbc.query("select id,event_type,occurred_at,published_at,attempts from outbox_events where aggregate_id=? order by occurred_at,id",(r,n)->new EventView(r.getObject(1,UUID.class),r.getString(2),r.getTimestamp(3).toInstant(),r.getTimestamp(4)==null?null:r.getTimestamp(4).toInstant(),r.getInt(5)),id);}

    void reconcile(){for(Transfer t:transfers.findTop50ByStatusAndUpdatedAtBeforeOrderByUpdatedAt("PROCESSING",Instant.now().minusSeconds(5)))try{LedgerClient.Journal j=ledger.find("transfer:"+t.id);markPosted(t.id,j.journalId(),"Reconciliation found the ledger journal");}catch(HttpClientErrorException.NotFound absent){retryStableCommand(t);}catch(RestClientException unavailable){}}

    private void retryStableCommand(Transfer t){try{LedgerClient.Journal j=ledger.post(t.id,t.sourceAccountId,t.destinationAccountNumber,t.amountMinor,t.userSubject,t.correlationId);markPosted(t.id,j.journalId(),"Reconciliation safely replayed the stable ledger command");}catch(RestClientResponseException rejected){if(rejected.getStatusCode().is4xxClientError())markFailed(t.id,"LEDGER_REJECTED_"+rejected.getStatusCode().value());}catch(RestClientException unavailable){}}
    void consumeJournal(UUID eventId,String businessReference,UUID journalId){
        if(!businessReference.startsWith("transfer:"))return;UUID transferId=UUID.fromString(businessReference.substring("transfer:".length()));
        tx.executeWithoutResult(s->{int accepted=jdbc.update("insert into inbox_events(event_id,event_type,processed_at) values (?,?,?) on conflict do nothing",eventId,"ledger.journal-posted.v1",Timestamp.from(Instant.now()));if(accepted==1&&transfers.existsById(transferId))markPostedInTransaction(transferId,journalId,"Ledger event confirmed the journal");});
    }

    private void markPosted(UUID id,UUID journal,String detail){tx.executeWithoutResult(s->markPostedInTransaction(id,journal,detail));}
    private void markPostedInTransaction(UUID id,UUID journal,String detail){Transfer t=transfers.findById(id).orElseThrow();if(t.status.equals("POSTED"))return;t.status="POSTED";t.failureCode=null;t.journalId=journal;t.updatedAt=Instant.now();timeline(t,"POSTED",detail);publish(t,"transfer.posted.v1");}
    private void markFailed(UUID id,String code){tx.executeWithoutResult(s->{Transfer t=transfers.findById(id).orElseThrow();if(t.status.equals("POSTED"))return;t.status="FAILED";t.failureCode=code;t.updatedAt=Instant.now();timeline(t,"FAILED",code);publish(t,"transfer.failed.v1");});}
    private void timelineOutside(UUID id,String stage,String detail){tx.executeWithoutResult(s->{Transfer t=transfers.findById(id).orElseThrow();t.updatedAt=Instant.now();timeline(t,stage,detail);});}
    private void timeline(Transfer t,String stage,String detail){jdbc.update("insert into transfer_timeline(transfer_id,stage,detail,correlation_id) values (?,?,?,?)",t.id,stage,detail,t.correlationId);}
    private void publish(Transfer t,String type){try{UUID eventId=UUID.randomUUID();Map<String,Object> envelope=Map.of("eventId",eventId,"eventType",type,"eventVersion",1,"aggregateId",t.id,"occurredAt",Instant.now(),"correlationId",t.correlationId,"causationId",t.id.toString(),"payload",Map.of("transferId",t.id,"status",t.status,"amountMinor",t.amountMinor,"currency",t.currency));jdbc.update("insert into outbox_events(id,aggregate_id,event_type,payload,occurred_at) values (?,?,?,cast(? as jsonb),?)",eventId,t.id,type,json.writeValueAsString(envelope),Timestamp.from(Instant.now()));}catch(Exception e){throw new IllegalStateException(e);}}
    private void assertFingerprint(Transfer t,String fingerprint){if(!MessageDigest.isEqual(t.requestFingerprint.getBytes(StandardCharsets.UTF_8),fingerprint.getBytes(StandardCharsets.UTF_8)))throw new ApiException(409,"idempotency_conflict","Idempotency key was already used with different request content");}
    private String hash(String v){try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(v.getBytes(StandardCharsets.UTF_8)));}catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}}
    private TransferView view(Transfer t){return new TransferView(t.id,t.sourceAccountId,t.destinationAccountNumber,t.amountMinor,t.currency,t.status,t.failureCode,t.journalId,t.correlationId,t.createdAt,t.updatedAt);}
    record CreateTransfer(UUID sourceAccountId,String destinationAccountNumber,long amountMinor){}
    record TransferView(UUID transferId,UUID sourceAccountId,String destinationAccountNumber,long amountMinor,String currency,String status,String failureCode,UUID journalId,String correlationId,Instant createdAt,Instant updatedAt){}
    record TimelineView(String stage,String detail,String correlationId,Instant occurredAt){}
    record EventView(UUID eventId,String eventType,Instant occurredAt,Instant publishedAt,int attempts){}
}
