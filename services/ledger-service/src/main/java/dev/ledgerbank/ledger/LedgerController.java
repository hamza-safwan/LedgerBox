package dev.ledgerbank.ledger;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.Instant;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class LedgerController {
    private final LedgerService ledger;
    LedgerController(LedgerService ledger){this.ledger=ledger;}

    @GetMapping("/api/v1/accounts")
    List<LedgerService.AccountView> accounts(@AuthenticationPrincipal Jwt jwt){return ledger.accounts(UUID.fromString(jwt.getSubject()));}

    @GetMapping("/api/v1/accounts/{id}")
    LedgerService.AccountView account(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return ledger.account(id,subject(jwt),operator(jwt));}

    @GetMapping("/api/v1/accounts/{id}/statement")
    LedgerService.StatementPage statement(@PathVariable UUID id,@RequestParam(required=false) Instant before,@RequestParam(defaultValue="50") int limit,@AuthenticationPrincipal Jwt jwt){return ledger.statement(id,subject(jwt),operator(jwt),before,limit);}

    @PostMapping("/api/v1/ops/accounts/{id}/sandbox-funding")
    ResponseEntity<LedgerService.JournalView> fund(@PathVariable UUID id,@RequestHeader("Idempotency-Key") @Size(min=8,max=100) String key,@Valid @RequestBody AmountRequest request,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="X-Correlation-ID",required=false) String cid){return ResponseEntity.status(201).body(ledger.fund(id,request.amountMinor(),key,jwt.getSubject(),cid));}

    @GetMapping("/api/v1/ops/accounts")
    List<LedgerService.OpsAccountView> opsAccounts(){return ledger.allCustomerAccounts();}

    @PatchMapping("/api/v1/ops/accounts/{id}/status")
    LedgerService.AccountView status(@PathVariable UUID id,@Valid @RequestBody StatusRequest request,@AuthenticationPrincipal Jwt jwt,@RequestHeader(value="X-Correlation-ID",required=false)String cid){return ledger.changeStatus(id,request.status(),jwt.getSubject(),cid);}

    @GetMapping("/api/v1/ops/journals/{id}")
    LedgerService.JournalDetail journal(@PathVariable UUID id){return ledger.journal(id);}

    @PostMapping("/internal/v1/postings/internal-transfer")
    LedgerService.JournalView transfer(@Valid @RequestBody InternalTransfer request,@RequestHeader(value="X-Correlation-ID",required=false)String cid){return ledger.internalTransfer(request.commandId(),request.sourceAccountId(),request.destinationAccountNumber(),request.amountMinor(),request.initiatedBySubject(),cid);}

    @PostMapping("/internal/v1/holds")
    LedgerService.HoldView hold(@Valid @RequestBody HoldRequest request,@RequestHeader(value="X-Correlation-ID",required=false)String cid){return ledger.createHold(request.commandId(),request.accountId(),request.amountMinor(),request.initiatedBySubject(),cid);}

    @PostMapping("/internal/v1/holds/{reference}/capture")
    LedgerService.JournalView capture(@PathVariable String reference,@RequestHeader(value="X-Correlation-ID",required=false)String cid){return ledger.captureHold(reference,cid);}

    @PostMapping("/internal/v1/holds/{reference}/release")
    ResponseEntity<Void> release(@PathVariable String reference,@RequestHeader(value="X-Correlation-ID",required=false)String cid){ledger.releaseHold(reference,cid);return ResponseEntity.noContent().build();}

    @PostMapping("/internal/v1/postings/ach-deposit")
    LedgerService.JournalView deposit(@Valid @RequestBody DepositRequest request,@RequestHeader(value="X-Correlation-ID",required=false)String cid){return ledger.achDeposit(request.commandId(),request.accountId(),request.amountMinor(),cid);}

    @GetMapping("/internal/v1/journals/by-reference")
    LedgerService.JournalView internalJournal(@RequestParam String reference){return ledger.journalByReference(reference);}

    @PostMapping("/internal/v1/journals/reverse")
    LedgerService.JournalView reverse(@Valid @RequestBody ReversalRequest request,@RequestHeader(value="X-Correlation-ID",required=false)String cid){return ledger.reverse(request.originalReference(),request.commandId(),cid);}

    private UUID subject(Jwt jwt){if(operator(jwt))return null;return UUID.fromString(jwt.getSubject());}
    private boolean operator(Jwt jwt){List<String> roles=jwt.getClaimAsStringList("roles");return roles!=null&&roles.contains("OPERATOR");}
    record AmountRequest(@Positive long amountMinor){}
    record StatusRequest(@Pattern(regexp="ACTIVE|FROZEN|CLOSED")String status){}
    record InternalTransfer(@NotNull UUID commandId,@NotNull UUID sourceAccountId,@Pattern(regexp="\\d{12}")String destinationAccountNumber,@Positive long amountMinor,@NotNull UUID initiatedBySubject){}
    record HoldRequest(@NotNull UUID commandId,@NotNull UUID accountId,@Positive long amountMinor,@NotNull UUID initiatedBySubject){}
    record DepositRequest(@NotNull UUID commandId,@NotNull UUID accountId,@Positive long amountMinor){}
    record ReversalRequest(@NotBlank String originalReference,@NotNull UUID commandId){}
}
