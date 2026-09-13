package dev.ledgerbank.movement;
import jakarta.validation.Valid;import jakarta.validation.constraints.*;import java.util.*;import org.springframework.http.*;import org.springframework.security.core.annotation.AuthenticationPrincipal;import org.springframework.security.oauth2.jwt.Jwt;import org.springframework.web.bind.annotation.*;
@RestController class TransferController{
 private final TransferService service;TransferController(TransferService service){this.service=service;}
 @PostMapping("/api/v1/transfers")ResponseEntity<TransferService.TransferView>create(@AuthenticationPrincipal Jwt jwt,@RequestHeader("Idempotency-Key")@Size(min=8,max=100)String key,@RequestHeader(value="X-Correlation-ID",required=false)String cid,@Valid @RequestBody Request r){return ResponseEntity.status(201).body(service.submit(UUID.fromString(jwt.getSubject()),new TransferService.CreateTransfer(r.sourceAccountId(),r.destinationAccountNumber(),r.amountMinor()),key,cid));}
 @GetMapping("/api/v1/transfers")List<TransferService.TransferView>list(@AuthenticationPrincipal Jwt jwt){return service.list(UUID.fromString(jwt.getSubject()));}
 @GetMapping("/api/v1/transfers/{id}")TransferService.TransferView get(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return service.get(id,subject(jwt),operator(jwt));}
 @GetMapping("/api/v1/ops/transfers")List<TransferService.TransferView>ops(){return service.opsList();}
 @GetMapping("/api/v1/ops/transfers/{id}")Map<String,Object>trace(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt){return Map.of("transfer",service.get(id,null,true),"timeline",service.timeline(id),"events",service.events(id));}
 private UUID subject(Jwt j){return operator(j)?null:UUID.fromString(j.getSubject());}private boolean operator(Jwt j){List<String>r=j.getClaimAsStringList("roles");return r!=null&&r.contains("OPERATOR");}
 record Request(@NotNull UUID sourceAccountId,@Pattern(regexp="\\d{12}")String destinationAccountNumber,@Positive long amountMinor){}
}
