package dev.ledgerbank.rail;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

@Component
class SandboxClock {
    private final JdbcTemplate jdbc;

    SandboxClock(JdbcTemplate jdbc) { this.jdbc = jdbc; }

    Instant now() {
        return jdbc.queryForObject("select simulated_at + case when running then clock_timestamp()-updated_at else interval '0 seconds' end from rail_clock where id=1",
                (result, row) -> result.getTimestamp(1).toInstant());
    }

    ClockView view() {
        return jdbc.queryForObject("select simulated_at + case when running then clock_timestamp()-updated_at else interval '0 seconds' end,running,updated_at from rail_clock where id=1",
                this::view);
    }

    @Transactional
    ClockView advance(long seconds) {
        if (seconds < 1 || seconds > 2_592_000) throw new ApiException(400,"invalid_clock_advance","Advance must be between one second and 30 days");
        ClockView current = locked();
        Instant advanced = current.currentTime().plusSeconds(seconds);
        jdbc.update("update rail_clock set simulated_at=?,updated_at=clock_timestamp() where id=1", Timestamp.from(advanced));
        return view();
    }

    @Transactional
    ClockView setRunning(boolean running) {
        ClockView current = locked();
        jdbc.update("update rail_clock set simulated_at=?,running=?,updated_at=clock_timestamp() where id=1",
                Timestamp.from(current.currentTime()),running);
        return view();
    }

    private ClockView locked() {
        return jdbc.queryForObject("select simulated_at + case when running then clock_timestamp()-updated_at else interval '0 seconds' end,running,updated_at from rail_clock where id=1 for update",
                this::view);
    }

    private ClockView view(ResultSet result, int row) throws SQLException {
        return new ClockView(result.getTimestamp(1).toInstant(),result.getBoolean(2),result.getTimestamp(3).toInstant());
    }

    record ClockView(Instant currentTime,boolean running,Instant updatedAt) {}
}
