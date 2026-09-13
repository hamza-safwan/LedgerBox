package dev.ledgerbank.ledger;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.*;
import java.time.Instant;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@Testcontainers(disabledWithoutDocker = true)
class LedgerMigrationTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine");

    @BeforeAll
    static void migrate() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
    }

    @Test
    void deferredConstraintRejectsAnUnbalancedJournalAtCommit() throws Exception {
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            UUID journal = insertJournal(connection, "unbalanced");
            insertPosting(connection, journal, LedgerService.SETTLEMENT_ACCOUNT, "DEBIT", 500);
            assertThrows(SQLException.class, connection::commit);
        }
    }

    @Test
    void balancedJournalCommitsAndPostingsCannotBeChanged() throws Exception {
        UUID customerAccount = UUID.randomUUID();
        UUID journal = UUID.randomUUID();
        UUID debitPosting = UUID.randomUUID();
        try (Connection connection = connection()) {
            connection.setAutoCommit(false);
            try (PreparedStatement account = connection.prepareStatement("insert into financial_accounts(id,owner_subject,public_account_number,name,account_type,status,currency,created_at) values (?,?,?,?,?,?,?,?)")) {
                account.setObject(1, customerAccount); account.setObject(2, UUID.randomUUID()); account.setString(3, "9" + System.nanoTime());
                account.setString(4, "Property checking"); account.setString(5, "LIABILITY"); account.setString(6, "ACTIVE");
                account.setString(7, "USD"); account.setTimestamp(8, Timestamp.from(Instant.now())); account.executeUpdate();
            }
            insertJournal(connection, journal, "balanced");
            insertPosting(connection, debitPosting, journal, LedgerService.SETTLEMENT_ACCOUNT, "DEBIT", 725);
            insertPosting(connection, UUID.randomUUID(), journal, customerAccount, "CREDIT", 725);
            connection.commit();
        }
        try (Connection connection = connection(); PreparedStatement update = connection.prepareStatement("update postings set amount_minor=726 where id=?")) {
            update.setObject(1, debitPosting);
            assertThrows(SQLException.class, update::executeUpdate);
        }
    }

    private static Connection connection() throws SQLException {
        return DriverManager.getConnection(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static UUID insertJournal(Connection connection, String suffix) throws SQLException {
        UUID id = UUID.randomUUID();
        insertJournal(connection, id, suffix);
        return id;
    }

    private static void insertJournal(Connection connection, UUID id, String suffix) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("insert into journals(id,business_reference,journal_type,description,initiated_by,correlation_id,created_at) values (?,?,?,?,?,?,?)")) {
            statement.setObject(1, id); statement.setString(2, "test:" + suffix + ":" + id); statement.setString(3, "TEST");
            statement.setString(4, "Invariant test"); statement.setString(5, "test-suite"); statement.setString(6, id.toString());
            statement.setTimestamp(7, Timestamp.from(Instant.now())); statement.executeUpdate();
        }
    }

    private static void insertPosting(Connection connection, UUID journal, UUID account, String direction, long amount) throws SQLException {
        insertPosting(connection, UUID.randomUUID(), journal, account, direction, amount);
    }

    private static void insertPosting(Connection connection, UUID id, UUID journal, UUID account, String direction, long amount) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("insert into postings(id,journal_id,account_id,direction,amount_minor,currency,created_at) values (?,?,?,?,?,?,?)")) {
            statement.setObject(1, id); statement.setObject(2, journal); statement.setObject(3, account); statement.setString(4, direction);
            statement.setLong(5, amount); statement.setString(6, "USD"); statement.setTimestamp(7, Timestamp.from(Instant.now())); statement.executeUpdate();
        }
    }
}
