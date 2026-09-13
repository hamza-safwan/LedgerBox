package dev.ledgerbank.customer;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.*;
import java.util.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
class CustomerService {
    private final CustomerRepository customers; private final PiiCrypto crypto;
    private final JdbcTemplate jdbc; private final ObjectMapper json;
    CustomerService(CustomerRepository customers, PiiCrypto crypto, JdbcTemplate jdbc, ObjectMapper json) {
        this.customers=customers; this.crypto=crypto; this.jdbc=jdbc; this.json=json;
    }

    @Transactional
    CustomerView upsert(UUID subject, ProfileRequest request, String correlationId) {
        CustomerProfile profile=customers.findByUserSubject(subject).orElse(null);
        if (profile == null) profile = new CustomerProfile(subject, crypto.encrypt(request.legalName()), crypto.encrypt(request.dateOfBirth().toString()), crypto.encrypt(request.address()));
        else {
            if (!profile.kycStatus.equals("DRAFT") && !profile.kycStatus.equals("REJECTED")) throw new ApiException(409,"profile_locked","Profile cannot be changed during or after verification");
            profile.legalName=crypto.encrypt(request.legalName()); profile.dateOfBirth=crypto.encrypt(request.dateOfBirth().toString()); profile.address=crypto.encrypt(request.address()); profile.updatedAt=Instant.now();
        }
        profile=customers.save(profile); audit(subject.toString(),"PROFILE_UPSERTED",profile.id,correlationId);
        return view(profile);
    }

    @Transactional(readOnly=true)
    CustomerView me(UUID subject) { return view(bySubject(subject)); }

    @Transactional
    KycView submit(UUID subject, String testIdentityCode, String correlationId) {
        CustomerProfile profile=bySubject(subject);
        if (!Set.of("DRAFT","REJECTED").contains(profile.kycStatus)) throw new ApiException(409,"invalid_kyc_state","KYC has already been submitted");
        profile.kycStatus="SUBMITTED";
        switch (testIdentityCode.toUpperCase(Locale.ROOT)) {
            case "APPROVE" -> { profile.kycStatus="APPROVED"; profile.kycReason=null; publish(profile,"customer.kyc-approved.v1",correlationId); }
            case "REJECT" -> { profile.kycStatus="REJECTED"; profile.kycReason="SYNTHETIC_IDENTITY_MISMATCH"; }
            default -> { profile.kycStatus="MANUAL_REVIEW"; profile.kycReason="SYNTHETIC_MANUAL_REVIEW"; }
        }
        profile.updatedAt=Instant.now(); audit(subject.toString(),"KYC_SUBMITTED",profile.id,correlationId);
        return new KycView(profile.id,profile.kycStatus,profile.kycReason,profile.updatedAt);
    }

    @Transactional
    KycView review(UUID customerId, String decision, String operator, String correlationId) {
        CustomerProfile profile=customers.findById(customerId).orElseThrow(() -> new ApiException(404,"customer_not_found","Customer was not found"));
        if (!profile.kycStatus.equals("MANUAL_REVIEW")) throw new ApiException(409,"invalid_kyc_state","Customer is not awaiting review");
        profile.kycStatus=decision.equals("APPROVE") ? "APPROVED" : "REJECTED";
        profile.kycReason=decision.equals("APPROVE") ? null : "OPERATOR_REJECTED"; profile.updatedAt=Instant.now();
        if (profile.kycStatus.equals("APPROVED")) publish(profile,"customer.kyc-approved.v1",correlationId);
        audit(operator,"KYC_"+profile.kycStatus,profile.id,correlationId);
        return new KycView(profile.id,profile.kycStatus,profile.kycReason,profile.updatedAt);
    }

    @Transactional(readOnly=true)
    List<OpsCustomerView> list() { return customers.findAll().stream().map(p -> new OpsCustomerView(p.id,p.userSubject,p.kycStatus,p.createdAt)).toList(); }

    private CustomerProfile bySubject(UUID subject) { return customers.findByUserSubject(subject).orElseThrow(() -> new ApiException(404,"profile_not_found","Complete your customer profile first")); }
    private CustomerView view(CustomerProfile p) { return new CustomerView(p.id,p.userSubject,crypto.decrypt(p.legalName),LocalDate.parse(crypto.decrypt(p.dateOfBirth)),crypto.decrypt(p.address),p.kycStatus,p.kycReason,p.updatedAt); }
    private void publish(CustomerProfile p,String type,String correlationId) {
        UUID eventId=UUID.randomUUID(); Map<String,Object> envelope=Map.of("eventId",eventId,"eventType",type,"eventVersion",1,"aggregateId",p.id,"occurredAt",Instant.now(),"correlationId",correlationId == null ? eventId.toString() : correlationId,"causationId",correlationId == null ? eventId.toString() : correlationId,"payload",Map.of("customerId",p.id,"userSubject",p.userSubject));
        try { jdbc.update("insert into outbox_events(id,aggregate_id,event_type,payload,occurred_at) values (?,?,?,cast(? as jsonb),?)",eventId,p.id,type,json.writeValueAsString(envelope),Timestamp.from(Instant.now())); }
        catch (JacksonException e) { throw new IllegalStateException(e); }
    }
    private void audit(String actor,String action,UUID resource,String correlationId) { jdbc.update("insert into customer_audit(actor_subject,action,resource_id,correlation_id) values (?,?,?,?)",actor,action,resource,correlationId); }

    record ProfileRequest(String legalName, LocalDate dateOfBirth, String address) {}
    record CustomerView(UUID customerId, UUID userSubject, String legalName, LocalDate dateOfBirth, String address, String kycStatus, String kycReason, Instant updatedAt) {}
    record KycView(UUID customerId,String status,String reason,Instant updatedAt) {}
    record OpsCustomerView(UUID customerId,UUID userSubject,String kycStatus,Instant createdAt) {}
}
