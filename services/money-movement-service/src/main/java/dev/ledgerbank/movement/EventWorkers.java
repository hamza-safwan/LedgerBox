package dev.ledgerbank.movement;
import tools.jackson.databind.*;import java.sql.ResultSet;import java.sql.Timestamp;import java.time.Instant;import java.util.*;import java.util.concurrent.TimeUnit;import org.springframework.jdbc.core.JdbcTemplate;import org.springframework.kafka.annotation.KafkaListener;import org.springframework.kafka.core.KafkaTemplate;import org.springframework.scheduling.annotation.Scheduled;import org.springframework.stereotype.Component;
@Component class EventWorkers{
 private final ObjectMapper json;private final TransferService transfers;private final JdbcTemplate jdbc;private final KafkaTemplate<String,String>kafka;
 EventWorkers(ObjectMapper json,TransferService transfers,JdbcTemplate jdbc,KafkaTemplate<String,String>kafka){this.json=json;this.transfers=transfers;this.jdbc=jdbc;this.kafka=kafka;}
 @KafkaListener(topics="ledger.events")void receive(String value)throws Exception{JsonNode e=json.readTree(value);if(!"ledger.journal-posted.v1".equals(e.path("eventType").asText()))return;JsonNode p=e.path("payload");transfers.consumeJournal(UUID.fromString(e.path("eventId").asText()),p.path("businessReference").asText(),UUID.fromString(p.path("journalId").asText()));}
 @Scheduled(fixedDelay=500)void outbox(){List<Row>rows=jdbc.query("select id,aggregate_id,payload::text from outbox_events where published_at is null order by occurred_at limit 50",(ResultSet r,int n)->new Row(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)));for(Row row:rows)try{kafka.send("movement.events",row.aggregate.toString(),row.payload).get(5,TimeUnit.SECONDS);jdbc.update("update outbox_events set published_at=?,attempts=attempts+1 where id=? and published_at is null",Timestamp.from(Instant.now()),row.id);}catch(Exception e){jdbc.update("update outbox_events set attempts=attempts+1 where id=?",row.id);}}
 @Scheduled(fixedDelay=5000)void reconcile(){transfers.reconcile();}
 record Row(UUID id,UUID aggregate,String payload){}
}
