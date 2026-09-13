package dev.ledgerbank.ledger;

import jakarta.validation.constraints.Size;
import java.util.List;
import java.util.UUID;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/v1/ops/ledger-dead-letters")
class DeadLetterController {
    private final DeadLetterOperations operations;
    DeadLetterController(DeadLetterOperations operations) { this.operations = operations; }

    @GetMapping
    List<DeadLetterOperations.DeadLetterView> list() { return operations.list(); }

    @PostMapping("/{id}/redrive")
    DeadLetterOperations.DeadLetterView redrive(@PathVariable UUID id,
            @RequestHeader("Idempotency-Key") @Size(min = 8, max = 100) String idempotencyKey) {
        return operations.redrive(id, idempotencyKey);
    }
}
