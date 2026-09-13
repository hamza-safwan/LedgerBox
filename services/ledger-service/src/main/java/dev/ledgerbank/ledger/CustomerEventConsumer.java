package dev.ledgerbank.ledger;

import tools.jackson.databind.*;
import java.util.UUID;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

@Component
class CustomerEventConsumer {
    private final ObjectMapper json;private final LedgerService ledger;
    CustomerEventConsumer(ObjectMapper json,LedgerService ledger){this.json=json;this.ledger=ledger;}
    @KafkaListener(topics="customer.events")
    void receive(String value)throws Exception{
        JsonNode event=json.readTree(value);if(!"customer.kyc-approved.v1".equals(event.path("eventType").asText()))return;
        JsonNode payload=event.path("payload");ledger.openAccount(UUID.fromString(event.path("eventId").asText()),UUID.fromString(payload.path("customerId").asText()),UUID.fromString(payload.path("userSubject").asText()),event.path("correlationId").asText());
    }
}
