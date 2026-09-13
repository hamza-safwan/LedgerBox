package dev.ledgerbank.customer;

import jakarta.validation.Valid;
import jakarta.validation.constraints.*;
import java.time.LocalDate;
import java.util.*;
import org.springframework.http.*;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.*;

@RestController
class CustomerController {
    private final CustomerService service;
    CustomerController(CustomerService service) { this.service=service; }

    @GetMapping("/api/v1/customers/me")
    CustomerService.CustomerView me(@AuthenticationPrincipal Jwt jwt) { return service.me(UUID.fromString(jwt.getSubject())); }

    @PutMapping("/api/v1/customers/me")
    CustomerService.CustomerView upsert(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody ProfileRequest request,
        @RequestHeader(value="X-Correlation-ID",required=false) String correlationId) {
        return service.upsert(UUID.fromString(jwt.getSubject()),new CustomerService.ProfileRequest(request.legalName(),request.dateOfBirth(),request.address()),correlationId);
    }

    @PostMapping("/api/v1/kyc-submissions")
    ResponseEntity<CustomerService.KycView> submit(@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody KycRequest request,
        @RequestHeader(value="X-Correlation-ID",required=false) String correlationId) {
        return ResponseEntity.accepted().body(service.submit(UUID.fromString(jwt.getSubject()),request.testIdentityCode(),correlationId));
    }

    @GetMapping("/api/v1/ops/customers")
    List<CustomerService.OpsCustomerView> customers() { return service.list(); }

    @PostMapping("/api/v1/ops/customers/{id}/kyc-review")
    CustomerService.KycView review(@PathVariable UUID id,@AuthenticationPrincipal Jwt jwt,@Valid @RequestBody ReviewRequest request,
        @RequestHeader(value="X-Correlation-ID",required=false) String correlationId) {
        return service.review(id,request.decision(),jwt.getSubject(),correlationId);
    }

    record ProfileRequest(@NotBlank @Size(max=160) String legalName,@Past LocalDate dateOfBirth,@NotBlank @Size(max=300) String address) {}
    record KycRequest(@NotBlank @Pattern(regexp="APPROVE|REJECT|REVIEW",flags=Pattern.Flag.CASE_INSENSITIVE) String testIdentityCode) {}
    record ReviewRequest(@Pattern(regexp="APPROVE|REJECT") String decision) {}
}

