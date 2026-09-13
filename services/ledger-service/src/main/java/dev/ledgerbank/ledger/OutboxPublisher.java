package dev.ledgerbank.ledger;

import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.TimeUnit;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

@Component
class OutboxPublisher {
    private final JdbcTemplate jdbc;private final KafkaTemplate<String,String> kafka;
    OutboxPublisher(JdbcTemplate jdbc,KafkaTemplate<String,String> kafka){this.jdbc=jdbc;this.kafka=kafka;}
    @Scheduled(fixedDelayString="${ledgerbank.outbox-delay-ms:500}")void publish(){
        List<Row> rows=jdbc.query("select id,aggregate_id,payload::text from outbox_events where published_at is null order by occurred_at limit 50",(ResultSet r,int n)->new Row(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3)));
        for(Row row:rows)try{kafka.send("ledger.events",row.aggregate.toString(),row.payload).get(5,TimeUnit.SECONDS);jdbc.update("update outbox_events set published_at=?,attempts=attempts+1 where id=? and published_at is null",Timestamp.from(Instant.now()),row.id);}catch(Exception e){jdbc.update("update outbox_events set attempts=attempts+1 where id=?",row.id);}
    }
    record Row(UUID id,UUID aggregate,String payload){}
}
