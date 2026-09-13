package dev.ledgerbank.movement;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class DeadLetterOperations {
    private static final String SOURCE_TOPIC = "ledger.events";
    private final JdbcTemplate jdbc;
    private final KafkaTemplate<String, String> kafka;

    DeadLetterOperations(JdbcTemplate jdbc, KafkaTemplate<String, String> kafka) {
        this.jdbc = jdbc;
        this.kafka = kafka;
    }

    @KafkaListener(topics = "ledger.events.dlt", groupId = "money-movement-dlt-indexer")
    void index(ConsumerRecord<String, String> record) {
        String fingerprint = sha256(record.topic() + "|" + Objects.toString(record.key(), "") + "|" + record.value());
        UUID id = UUID.nameUUIDFromBytes(fingerprint.getBytes(StandardCharsets.UTF_8));
        jdbc.update("insert into dead_letters(id,source_topic,message_key,payload,payload_hash,received_at) values (?,?,?,?,?,?) on conflict do nothing",
                id, SOURCE_TOPIC, record.key(), record.value(), fingerprint, Timestamp.from(Instant.now()));
    }

    List<DeadLetterView> list() {
        return jdbc.query("select id,source_topic,message_key,payload,received_at,redriven_at from dead_letters order by received_at desc limit 100",
                (row, number) -> new DeadLetterView(row.getObject(1, UUID.class), row.getString(2), row.getString(3), row.getString(4),
                        row.getTimestamp(5).toInstant(), row.getTimestamp(6) == null ? null : row.getTimestamp(6).toInstant()));
    }

    @Transactional
    DeadLetterView redrive(UUID id, String idempotencyKey) {
        Row row = jdbc.query("select source_topic,message_key,payload,received_at,redriven_at,redrive_key from dead_letters where id=? for update",
                (result, number) -> new Row(result.getString(1), result.getString(2), result.getString(3), result.getTimestamp(4).toInstant(),
                        result.getTimestamp(5) == null ? null : result.getTimestamp(5).toInstant(), result.getString(6)), id)
                .stream().findFirst().orElseThrow(() -> new ApiException(404, "dead_letter_not_found", "Dead letter was not found"));
        if (row.redrivenAt != null) {
            if (!Objects.equals(row.redriveKey, idempotencyKey)) throw new ApiException(409, "idempotency_conflict", "Dead letter was already redriven with a different key");
            return new DeadLetterView(id, row.sourceTopic, row.messageKey, row.payload, row.receivedAt, row.redrivenAt);
        }
        try {
            kafka.send(row.sourceTopic, row.messageKey, row.payload).get(5, TimeUnit.SECONDS);
        } catch (Exception failure) {
            throw new ApiException(503, "kafka_unavailable", "Kafka did not acknowledge the redrive");
        }
        Instant now = Instant.now();
        jdbc.update("update dead_letters set redriven_at=?,redrive_key=? where id=?", Timestamp.from(now), idempotencyKey, id);
        return new DeadLetterView(id, row.sourceTopic, row.messageKey, row.payload, row.receivedAt, now);
    }

    private String sha256(String value) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8))); }
        catch (Exception impossible) { throw new IllegalStateException(impossible); }
    }

    record Row(String sourceTopic, String messageKey, String payload, Instant receivedAt, Instant redrivenAt, String redriveKey) {}
    record DeadLetterView(UUID deadLetterId, String sourceTopic, String messageKey, String payload, Instant receivedAt, Instant redrivenAt) {}
}
