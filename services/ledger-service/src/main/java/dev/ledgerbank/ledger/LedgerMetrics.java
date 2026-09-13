package dev.ledgerbank.ledger;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

@Component
class LedgerMetrics {
    LedgerMetrics(MeterRegistry registry, JdbcTemplate jdbc) {
        Gauge.builder("ledgerbank.outbox.oldest.age", jdbc, LedgerMetrics::outboxAgeSeconds)
                .description("Age of the oldest unpublished ledger outbox event")
                .baseUnit("seconds")
                .register(registry);
        Gauge.builder("ledgerbank.consumer.failures", jdbc, template -> count(template,
                        "select count(*) from dead_letters where redriven_at is null"))
                .description("Unresolved poison events in the ledger dead-letter store")
                .register(registry);
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
