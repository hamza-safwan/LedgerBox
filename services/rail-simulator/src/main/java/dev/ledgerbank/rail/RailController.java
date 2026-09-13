package dev.ledgerbank.rail;

import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

@RestController
class RailController {
    private final RailService service;
    private final SandboxClock clock;

    RailController(RailService service, SandboxClock clock) { this.service = service; this.clock = clock; }

    @PostMapping("/api/v1/external-accounts")
    ResponseEntity<RailService.ExternalView> link(@AuthenticationPrincipal Jwt jwt, @Valid @RequestBody LinkRequest request) {
        return ResponseEntity.status(201).body(service.link(UUID.fromString(jwt.getSubject()), request.institutionName(), request.last4()));
    }

    @GetMapping("/api/v1/external-accounts")
    List<RailService.ExternalView> links(@AuthenticationPrincipal Jwt jwt) { return service.externalAccounts(UUID.fromString(jwt.getSubject())); }

    @PostMapping("/api/v1/ach-transfers")
    ResponseEntity<RailService.AchView> create(@AuthenticationPrincipal Jwt jwt,
                                               @RequestHeader("Idempotency-Key") @Size(min=8,max=100) String key,
                                               @RequestHeader(value="X-Correlation-ID",required=false) String correlationId,
                                               @Valid @RequestBody AchRequest request) {
        return ResponseEntity.status(201).body(service.create(UUID.fromString(jwt.getSubject()),
                new RailService.CreateAch(request.ledgerAccountId(), request.externalAccountId(), request.direction(), request.amountMinor(), request.scenario()),
                key, correlationId));
    }

    @GetMapping("/api/v1/ach-transfers")
    List<RailService.AchView> list(@AuthenticationPrincipal Jwt jwt) { return service.list(UUID.fromString(jwt.getSubject())); }

    @GetMapping("/api/v1/ach-transfers/{id}")
    RailService.AchView get(@PathVariable UUID id, @AuthenticationPrincipal Jwt jwt) { return service.get(subject(jwt), id, operator(jwt)); }

    @GetMapping("/api/v1/ops/ach-transfers")
    List<RailService.AchView> ops() { return service.ops(); }

    @GetMapping("/api/v1/ops/ach-transfers/{id}")
    Map<String,Object> trace(@PathVariable UUID id) { return Map.of("transfer", service.get(null,id,true), "timeline", service.timeline(id)); }

    @GetMapping("/api/v1/ops/settlement-batches")
    List<RailService.SettlementBatchView> batches() { return service.batches(); }

    @PostMapping("/api/v1/ops/settlement-batches/{id}/reconcile")
    RailService.SettlementBatchView reconcile(@PathVariable UUID id) { return service.reconcileBatch(id); }

    @GetMapping("/api/v1/ops/rail-clock")
    SandboxClock.ClockView clock() { return clock.view(); }

    @PostMapping("/api/v1/ops/rail-clock/advance")
    SandboxClock.ClockView advance(@Valid @RequestBody AdvanceClockRequest request) {
        SandboxClock.ClockView advanced = clock.advance(request.seconds());
        service.advanceDue();
        return advanced;
    }

    @PatchMapping("/api/v1/ops/rail-clock")
    SandboxClock.ClockView clockStatus(@Valid @RequestBody ClockStatusRequest request) { return clock.setRunning(request.running()); }

    private boolean operator(Jwt jwt) {
        List<String> roles = jwt.getClaimAsStringList("roles");
        return roles != null && roles.contains("OPERATOR");
    }

    private UUID subject(Jwt jwt) { return operator(jwt) ? null : UUID.fromString(jwt.getSubject()); }

    record LinkRequest(@NotBlank @Size(max=100) String institutionName, @Pattern(regexp="\\d{4}") String last4) { }
    record AchRequest(@NotNull UUID ledgerAccountId, @NotNull UUID externalAccountId,
                      @Pattern(regexp="DEPOSIT|WITHDRAWAL") String direction, @Positive long amountMinor,
                      @Pattern(regexp="SETTLE|REJECT|RETURN") String scenario) { }
    record AdvanceClockRequest(@Min(1) @Max(2_592_000) long seconds) { }
    record ClockStatusRequest(@NotNull Boolean running) { }
}
