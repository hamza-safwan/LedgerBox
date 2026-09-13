package dev.ledgerbank.movement;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class MovementMetrics {
    MovementMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        Gauge.builder("ledgerbank.outbox.oldest.age", jdbc, MovementMetrics::outboxAgeSeconds)
                .description("Age of the oldest unpublished movement outbox event")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("ledgerbank.consumer.failures", jdbc, template -> count(template,
                        "select count(*) from dead_letters where redriven_at is null"))
                .description("Unresolved poison events in the movement dead-letter store")
                .register(registry);
        Gauge.builder("ledgerbank.reconciliation.mismatches", jdbc, template -> count(template,
                        "select count(*) from transfers where status='PROCESSING' and updated_at < now()-interval '10 seconds'"))
                .description("Transfers whose ledger outcome still requires reconciliation")
                .register(registry);
        for (String status : List.of("RECEIVED", "PROCESSING", "POSTED", "FAILED")) {
            Gauge.builder("ledgerbank.transfer.outcomes", jdbc, template -> count(template,
                            "select count(*) from transfers where status='" + status + "'"))
                    .description("Current transfer aggregates by terminal or intermediate outcome")
                    .tag("status", status.toLowerCase())
                    .register(registry);
        }
    }

    private static double outboxAgeSeconds(JdbcTemplate jdbc) {
        Double value = jdbc.queryForObject("select coalesce(extract(epoch from (clock_timestamp()-min(occurred_at))),0) from outbox_events where published_at is null", Double.class);
        return value == null ? 0 : value;
    }

    private static double count(JdbcTemplate jdbc, String sql) {
        Long value = jdbc.queryForObject(sql, Long.class);
        return value == null ? 0 : value;
    }
}
