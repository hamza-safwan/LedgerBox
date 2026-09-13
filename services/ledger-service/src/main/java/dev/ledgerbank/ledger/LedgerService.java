package dev.ledgerbank.ledger;

import tools.jackson.databind.ObjectMapper;
import java.sql.*;
import java.time.*;
import java.util.*;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class LedgerService {
    static final UUID SETTLEMENT_ACCOUNT=UUID.fromString("00000000-0000-0000-0000-000000000001");
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    LedgerService(JdbcTemplate jdbc,ObjectMapper json){this.jdbc=jdbc;this.json=json;}

    @Transactional
    AccountView openAccount(UUID eventId,UUID customerId,UUID owner,String correlationId){
        int accepted=jdbc.update("insert into inbox_events(event_id,event_type,processed_at) values (?,?,?) on conflict do nothing",eventId,"customer.kyc-approved.v1",Timestamp.from(Instant.now()));
        if(accepted==0)return accounts(owner).stream().findFirst().orElseThrow();
        List<AccountView> existing=accounts(owner); if(!existing.isEmpty())return existing.get(0);
        UUID id=UUID.randomUUID();String number=accountNumber();Instant now=Instant.now();
        jdbc.update("insert into financial_accounts(id,owner_subject,public_account_number,name,account_type,status,currency,created_at) values (?,?,?,?,?,?,?,?)",id,owner,number,"Everyday checking","LIABILITY","ACTIVE","USD",Timestamp.from(now));
        publish(id,"account.opened.v1",correlationId,Map.of("accountId",id,"customerId",customerId,"ownerSubject",owner,"publicAccountNumber",number));
        audit("ledger-service","ACCOUNT_OPENED",id,correlationId);return new AccountView(id,number,"Everyday checking","ACTIVE","USD",0,0,now);
    }

    List<AccountView> accounts(UUID owner){return jdbc.query("select id,public_account_number,name,status,currency,posted_balance_minor,available_balance_minor,created_at from financial_accounts where owner_subject=? order by created_at",this::accountView,owner);}
    List<OpsAccountView> allCustomerAccounts(){return jdbc.query("select id,owner_subject,public_account_number,name,status,currency,posted_balance_minor,available_balance_minor,created_at from financial_accounts where owner_subject is not null order by created_at desc",(r,n)->new OpsAccountView(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getString(6),r.getLong(7),r.getLong(8),r.getTimestamp(9).toInstant()));}
    AccountView account(UUID id,UUID owner,boolean operator){return jdbc.query("select id,public_account_number,name,status,currency,posted_balance_minor,available_balance_minor,created_at from financial_accounts where id=? and (? or owner_subject=?)",this::accountView,id,operator,owner).stream().findFirst().orElseThrow(()->new ApiException(404,"account_not_found","Account was not found"));}

    @Transactional
    AccountView changeStatus(UUID id,String status,String operator,String correlationId){
        if(!Set.of("ACTIVE","FROZEN","CLOSED").contains(status))throw new ApiException(400,"invalid_account_status","Unsupported account status");
        int changed=jdbc.update("update financial_accounts set status=? where id=? and owner_subject is not null",status,id);
        if(changed==0)throw new ApiException(404,"account_not_found","Account was not found");
        audit(operator,"ACCOUNT_"+status,id,correlationId==null?UUID.randomUUID().toString():correlationId);
        return account(id,null,true);
    }

    StatementPage statement(UUID id,UUID owner,boolean operator,Instant before,int limit){
        account(id,owner,operator);int safe=Math.min(Math.max(limit,1),100);Instant cursor=before==null?Instant.now().plusSeconds(1):before;
        List<StatementLine> lines=jdbc.query("select p.id as posting_id,j.id as journal_id,j.journal_type,j.description,p.direction,p.amount_minor,p.currency,p.created_at,j.business_reference from postings p join journals j on j.id=p.journal_id where p.account_id=? and p.created_at<? order by p.created_at desc,p.id desc limit ?",(rs,n)->{
            String direction=rs.getString("direction");long amount=rs.getLong("amount_minor");
            long delta=(direction.equals("CREDIT")?1:-1)*amount;
            return new StatementLine(rs.getObject("posting_id",UUID.class),rs.getObject("journal_id",UUID.class),rs.getString("journal_type"),rs.getString("description"),delta,rs.getString("currency"),rs.getTimestamp("created_at").toInstant(),rs.getString("business_reference"));
        },id,Timestamp.from(cursor),safe);
        String next=lines.size()==safe?lines.get(lines.size()-1).bookedAt().toString():null;return new StatementPage(lines,next);
    }

    @Transactional
    JournalView fund(UUID accountId,long amount,String key,String operator,String correlationId){
        validateAmount(amount);String ref="funding:"+accountId+":"+key;return post(ref,"SANDBOX_FUNDING","Operator sandbox funding",operator,correlationId,SETTLEMENT_ACCOUNT,accountId,amount,false);
    }

    @Transactional
    JournalView internalTransfer(UUID commandId,UUID sourceId,String destinationNumber,long amount,UUID initiatedBy,String correlationId){
        validateAmount(amount);UUID destination=jdbc.query("select id from financial_accounts where public_account_number=?",rs->rs.next()?rs.getObject(1,UUID.class):null,destinationNumber);
        if(destination==null)throw new ApiException(404,"destination_not_found","Destination account was not found");
        if(sourceId.equals(destination))throw new ApiException(400,"same_account","Source and destination must differ");
        return post("transfer:"+commandId,"INTERNAL_TRANSFER","Internal account transfer",initiatedBy.toString(),correlationId,sourceId,destination,amount,true);
    }

    @Transactional
    HoldView createHold(UUID commandId,UUID accountId,long amount,UUID initiatedBy,String correlationId){
        validateAmount(amount);String ref="hold:"+commandId;lockReference(ref);
        List<HoldView> prior=jdbc.query("select id,account_id,business_reference,amount_minor,status,expires_at,created_at from funds_holds where business_reference=?",this::holdView,ref);if(!prior.isEmpty())return prior.get(0);
        AccountRow account=lockedAccounts(List.of(accountId)).get(0);assertOwner(account,initiatedBy);assertActive(account);if(account.available<amount)throw new ApiException(422,"insufficient_funds","Available balance is insufficient");
        UUID id=UUID.randomUUID();Instant now=Instant.now(),expires=now.plus(Duration.ofDays(2));
        jdbc.update("update financial_accounts set available_balance_minor=available_balance_minor-? where id=?",amount,accountId);
        jdbc.update("insert into funds_holds(id,account_id,business_reference,amount_minor,status,expires_at,created_at) values (?,?,?,?,?,?,?)",id,accountId,ref,amount,"ACTIVE",Timestamp.from(expires),Timestamp.from(now));
        audit(initiatedBy.toString(),"HOLD_CREATED",id,correlationId);return new HoldView(id,accountId,ref,amount,"ACTIVE",expires,now);
    }

    @Transactional
    JournalView captureHold(String holdReference,String correlationId){
        HoldRow hold=lockHold(holdReference);if(hold.status.equals("CAPTURED"))return journalByReference("withdrawal:"+holdReference);if(!hold.status.equals("ACTIVE"))throw new ApiException(409,"hold_not_active","Hold is not active");
        JournalView journal=post("withdrawal:"+holdReference,"ACH_WITHDRAWAL","Simulated ACH withdrawal","rail-simulator",correlationId,hold.accountId,SETTLEMENT_ACCOUNT,hold.amount,false);
        jdbc.update("update funds_holds set status='CAPTURED',resolved_at=? where id=?",Timestamp.from(Instant.now()),hold.id);
        jdbc.update("update financial_accounts set available_balance_minor=available_balance_minor+? where id=?",hold.amount,hold.accountId);
        return journal;
    }

    @Transactional
    void releaseHold(String holdReference,String correlationId){HoldRow hold=lockHold(holdReference);if(!hold.status.equals("ACTIVE"))return;jdbc.update("update funds_holds set status='RELEASED',resolved_at=? where id=?",Timestamp.from(Instant.now()),hold.id);jdbc.update("update financial_accounts set available_balance_minor=available_balance_minor+? where id=?",hold.amount,hold.accountId);audit("rail-simulator","HOLD_RELEASED",hold.id,correlationId);}

    @Transactional
    JournalView achDeposit(UUID commandId,UUID accountId,long amount,String correlationId){validateAmount(amount);return post("deposit:"+commandId,"ACH_DEPOSIT","Simulated ACH deposit","rail-simulator",correlationId,SETTLEMENT_ACCOUNT,accountId,amount,false);}

    @Transactional
    JournalView reverse(String originalReference,UUID commandId,String correlationId){
        String ref="reversal:"+commandId;lockReference(ref);List<JournalView> prior=jdbc.query("select id,business_reference,journal_type,description,correlation_id,created_at from journals where business_reference=?",this::journalView,ref);if(!prior.isEmpty())return prior.get(0);
        JournalView original=journalByReference(originalReference);List<PostingRow> originalPostings=jdbc.query("select account_id,direction,amount_minor from postings where journal_id=? order by account_id",(r,n)->new PostingRow(r.getObject(1,UUID.class),r.getString(2),r.getLong(3)),original.journalId);
        if(originalPostings.size()!=2)throw new ApiException(409,"unsupported_reversal","Only two-posting journals can be reversed automatically");
        Map<UUID,AccountRow> accounts=new HashMap<>();lockedAccounts(originalPostings.stream().map(PostingRow::accountId).toList()).forEach(a->accounts.put(a.id,a));
        UUID journalId=UUID.randomUUID();Instant now=Instant.now();String cid=correlationId==null?journalId.toString():correlationId;
        jdbc.update("insert into journals(id,business_reference,journal_type,description,initiated_by,correlation_id,reversal_of,created_at) values (?,?,?,?,?,?,?,?)",journalId,ref,"ACH_RETURN","Reversal of "+originalReference,"rail-simulator",cid,original.journalId,Timestamp.from(now));
        for(PostingRow p:originalPostings){String opposite=p.direction.equals("DEBIT")?"CREDIT":"DEBIT";insertPosting(journalId,p.accountId,opposite,p.amount,now);applyBalance(accounts.get(p.accountId),opposite,p.amount);}
        publish(journalId,"ledger.journal-posted.v1",cid,Map.of("journalId",journalId,"businessReference",ref,"journalType","ACH_RETURN","reversalOf",original.journalId));audit("rail-simulator","JOURNAL_REVERSED",journalId,cid);return new JournalView(journalId,ref,"ACH_RETURN","Reversal of "+originalReference,cid,now);
    }

    JournalDetail journal(UUID id){
        JournalView journal=jdbc.query("select id,business_reference,journal_type,description,correlation_id,created_at from journals where id=?",this::journalView,id).stream().findFirst().orElseThrow(()->new ApiException(404,"journal_not_found","Journal was not found"));
        List<PostingView> postings=jdbc.query("select id,account_id,direction,amount_minor,currency,created_at from postings where journal_id=? order by direction,account_id",(r,n)->new PostingView(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getLong(4),r.getString(5),r.getTimestamp(6).toInstant()),id);
        return new JournalDetail(journal,postings);
    }
    JournalView journalByReference(String ref){return jdbc.query("select id,business_reference,journal_type,description,correlation_id,created_at from journals where business_reference=?",this::journalView,ref).stream().findFirst().orElseThrow(()->new ApiException(404,"journal_not_found","Journal was not found"));}

    private JournalView post(String ref,String type,String description,String actor,String correlationId,UUID debitId,UUID creditId,long amount,boolean enforceOwner){
        lockReference(ref);List<JournalView> existing=jdbc.query("select id,business_reference,journal_type,description,correlation_id,created_at from journals where business_reference=?",this::journalView,ref);if(!existing.isEmpty())return existing.get(0);
        Map<UUID,AccountRow> accounts=new HashMap<>();lockedAccounts(List.of(debitId,creditId)).forEach(a->accounts.put(a.id,a));
        AccountRow debit=Optional.ofNullable(accounts.get(debitId)).orElseThrow(()->new ApiException(404,"account_not_found","Debit account was not found"));AccountRow credit=Optional.ofNullable(accounts.get(creditId)).orElseThrow(()->new ApiException(404,"account_not_found","Credit account was not found"));
        assertActive(debit);assertActive(credit);if(!debit.currency.equals(credit.currency))throw new ApiException(422,"currency_mismatch","Accounts must use the same currency");
        if(enforceOwner){assertOwner(debit,UUID.fromString(actor));if(debit.available<amount)throw new ApiException(422,"insufficient_funds","Available balance is insufficient");}
        UUID journalId=UUID.randomUUID();Instant now=Instant.now();String cid=correlationId==null?journalId.toString():correlationId;
        jdbc.update("insert into journals(id,business_reference,journal_type,description,initiated_by,correlation_id,created_at) values (?,?,?,?,?,?,?)",journalId,ref,type,description,actor,cid,Timestamp.from(now));
        insertPosting(journalId,debitId,"DEBIT",amount,now);insertPosting(journalId,creditId,"CREDIT",amount,now);
        applyBalance(debit,"DEBIT",amount);applyBalance(credit,"CREDIT",amount);
        publish(journalId,"ledger.journal-posted.v1",cid,Map.of("journalId",journalId,"businessReference",ref,"journalType",type));audit(actor,"JOURNAL_POSTED",journalId,cid);
        return new JournalView(journalId,ref,type,description,cid,now);
    }

    private List<AccountRow> lockedAccounts(List<UUID> ids){List<UUID> sorted=ids.stream().distinct().sorted().toList();String placeholders=String.join(",",Collections.nCopies(sorted.size(),"?"));return jdbc.query("select id,owner_subject,account_type,status,currency,posted_balance_minor,available_balance_minor from financial_accounts where id in ("+placeholders+") order by id for update",this::accountRow,sorted.toArray());}
    private HoldRow lockHold(String ref){return jdbc.query("select id,account_id,amount_minor,status from funds_holds where business_reference=? for update",(r,n)->new HoldRow(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getLong(3),r.getString(4)),ref).stream().findFirst().orElseThrow(()->new ApiException(404,"hold_not_found","Hold was not found"));}
    private void lockReference(String ref){jdbc.queryForObject("select pg_advisory_xact_lock(hashtextextended(?,0))",Object.class,ref);}
    private void insertPosting(UUID journal,UUID account,String direction,long amount,Instant now){jdbc.update("insert into postings(id,journal_id,account_id,direction,amount_minor,currency,created_at) values (?,?,?,?,?,'USD',?)",UUID.randomUUID(),journal,account,direction,amount,Timestamp.from(now));}
    private void applyBalance(AccountRow account,String direction,long amount){long delta=LedgerMath.balanceDelta(account.type,direction,amount);jdbc.update("update financial_accounts set posted_balance_minor=posted_balance_minor+?,available_balance_minor=available_balance_minor+? where id=?",delta,delta,account.id);}
    private void assertOwner(AccountRow account,UUID owner){if(account.owner==null||!account.owner.equals(owner))throw new ApiException(403,"account_ownership","Source account is not owned by the initiating customer");}
    private void assertActive(AccountRow account){if(!account.status.equals("ACTIVE"))throw new ApiException(409,"account_unavailable","Account is not active");}
    private void validateAmount(long amount){if(amount<=0)throw new ApiException(400,"invalid_amount","Amount must be positive");}
    private String accountNumber(){return String.format("%012d",Math.floorMod(new java.security.SecureRandom().nextLong(),1_000_000_000_000L));}
    private void publish(UUID aggregate,String type,String correlationId,Map<String,Object> payload){try{UUID eventId=UUID.randomUUID();String cid=correlationId==null?eventId.toString():correlationId;Map<String,Object> envelope=Map.of("eventId",eventId,"eventType",type,"eventVersion",1,"aggregateId",aggregate,"occurredAt",Instant.now(),"correlationId",cid,"causationId",aggregate.toString(),"payload",payload);jdbc.update("insert into outbox_events(id,aggregate_id,event_type,payload,occurred_at) values (?,?,?,cast(? as jsonb),?)",eventId,aggregate,type,json.writeValueAsString(envelope),Timestamp.from(Instant.now()));}catch(Exception e){throw new IllegalStateException(e);}}
    private void audit(String actor,String action,UUID resource,String cid){jdbc.update("insert into ledger_audit(actor_subject,action,resource_id,correlation_id) values (?,?,?,?)",actor,action,resource,cid);}
    private AccountRow accountRow(ResultSet r,int n)throws SQLException{return new AccountRow(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),r.getLong(7));}
    private AccountView accountView(ResultSet r,int n)throws SQLException{return new AccountView(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getLong(6),r.getLong(7),r.getTimestamp(8).toInstant());}
    private JournalView journalView(ResultSet r,int n)throws SQLException{return new JournalView(r.getObject(1,UUID.class),r.getString(2),r.getString(3),r.getString(4),r.getString(5),r.getTimestamp(6).toInstant());}
    private HoldView holdView(ResultSet r,int n)throws SQLException{return new HoldView(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getLong(4),r.getString(5),r.getTimestamp(6).toInstant(),r.getTimestamp(7).toInstant());}

    record AccountRow(UUID id,UUID owner,String type,String status,String currency,long posted,long available){}
    record HoldRow(UUID id,UUID accountId,long amount,String status){}
    record PostingRow(UUID accountId,String direction,long amount){}
    record AccountView(UUID accountId,String accountNumber,String name,String status,String currency,long postedBalanceMinor,long availableBalanceMinor,Instant openedAt){}
    record OpsAccountView(UUID accountId,UUID ownerSubject,String accountNumber,String name,String status,String currency,long postedBalanceMinor,long availableBalanceMinor,Instant openedAt){}
    record JournalView(UUID journalId,String businessReference,String journalType,String description,String correlationId,Instant bookedAt){}
    record PostingView(UUID postingId,UUID accountId,String direction,long amountMinor,String currency,Instant bookedAt){}
    record JournalDetail(JournalView journal,List<PostingView> postings){}
    record StatementLine(UUID postingId,UUID journalId,String type,String description,long amountMinor,String currency,Instant bookedAt,String reference){}
    record StatementPage(List<StatementLine> items,String nextCursor){}
    record HoldView(UUID holdId,UUID accountId,String businessReference,long amountMinor,String status,Instant expiresAt,Instant createdAt){}
}
