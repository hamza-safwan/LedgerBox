package dev.ledgerbank.rail;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class RailMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine");

    @BeforeAll
    static void migrate() {
        var result = Flyway.configure()
                .dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword())
                .load()
                .migrate();
        assertEquals(3, result.migrationsExecuted);
    }

    @Test
    void createsControllableClockAndSettlementBatchSchema() throws Exception {
        try (Connection connection = connection()) {
            assertTrue(tableExists(connection, "rail_clock"));
            assertTrue(tableExists(connection, "settlement_batches"));
            assertTrue(tableExists(connection, "settlement_batch_entries"));

            try (PreparedStatement statement = connection.prepareStatement(
                    "select running,simulated_at is not null from rail_clock where id=1");
                 ResultSet row = statement.executeQuery()) {
                assertTrue(row.next());
                assertTrue(row.getBoolean(1));
                assertTrue(row.getBoolean(2));
            }
        }
    }

    @Test
    void clockRemainsASingletonAndBatchStatusesAreConstrained() throws Exception {
        try (Connection connection = connection();
             PreparedStatement secondClock = connection.prepareStatement(
                     "insert into rail_clock(id,simulated_at,running,updated_at) values (2,now(),true,now())")) {
            assertThrows(SQLException.class, secondClock::executeUpdate);
        }

        try (Connection connection = connection();
             PreparedStatement invalidBatch = connection.prepareStatement(
                     "insert into settlement_batches(id,status,opened_at) values (gen_random_uuid(),'UNKNOWN',now())")) {
            assertThrows(SQLException.class, invalidBatch::executeUpdate);
        }
    }

    private static boolean tableExists(Connection connection, String table) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(
                "select exists(select 1 from information_schema.tables where table_schema='public' and table_name=?)")) {
            statement.setString(1, table);
            try (ResultSet row = statement.executeQuery()) {
                row.next();
                return row.getBoolean(1);
            }
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}
