package dev.ledgerbank.ledger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import tools.jackson.databind.json.JsonMapper;

@Testcontainers(disabledWithoutDocker = true)
class LedgerConcurrencyTest {
    @Container
    static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:18.6-alpine");

    static JdbcTemplate jdbc;
    static TransactionTemplate transaction;
    static LedgerService ledger;

    @BeforeAll
    static void startDatabase() {
        Flyway.configure().dataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword()).load().migrate();
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setURL(POSTGRES.getJdbcUrl());
        dataSource.setUser(POSTGRES.getUsername());
        dataSource.setPassword(POSTGRES.getPassword());
        jdbc = new JdbcTemplate(dataSource);
        transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
        ledger = new LedgerService(jdbc, JsonMapper.builder().findAndAddModules().build());
    }

    @BeforeEach
    void cleanDomainRows() {
        jdbc.execute("truncate table postings,journals,funds_holds,outbox_events,inbox_events,ledger_audit cascade");
        jdbc.update("delete from financial_accounts where owner_subject is not null");
        jdbc.update("update financial_accounts set posted_balance_minor=0,available_balance_minor=0 where id=?", LedgerService.SETTLEMENT_ACCOUNT);
    }

    @Test
    void competingTransfersCannotOverdrawOrCreateAnUnbalancedJournal() throws Exception {
        UUID owner = UUID.randomUUID();
        LedgerService.AccountView source = open(owner);
        LedgerService.AccountView destinationOne = open(UUID.randomUUID());
        LedgerService.AccountView destinationTwo = open(UUID.randomUUID());
        inTransaction(() -> ledger.fund(source.accountId(), 10_000, "concurrency-funding", "test-operator", "concurrency-test"));

        CountDownLatch ready = new CountDownLatch(2);
        CountDownLatch start = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            List<Future<String>> futures = new ArrayList<>();
            futures.add(pool.submit(transfer(ready, start, owner, source, destinationOne)));
            futures.add(pool.submit(transfer(ready, start, owner, source, destinationTwo)));
            ready.await();
            start.countDown();

            List<String> outcomes = futures.stream().map(future -> {
                try { return future.get(); }
                catch (Exception e) { throw new AssertionError(e); }
            }).sorted().toList();
            assertEquals(List.of("POSTED", "insufficient_funds"), outcomes);
        } finally {
            pool.shutdownNow();
        }

        assertEquals(2_000L, jdbc.queryForObject("select available_balance_minor from financial_accounts where id=?", Long.class, source.accountId()));
        assertEquals(1, jdbc.queryForObject("select count(*) from journals where business_reference like 'transfer:%'", Integer.class));
        Integer unbalanced = jdbc.queryForObject("select count(*) from (select journal_id from postings group by journal_id having sum(amount_minor) filter(where direction='DEBIT') <> sum(amount_minor) filter(where direction='CREDIT')) totals", Integer.class);
        assertEquals(0, unbalanced);
    }

    private Callable<String> transfer(CountDownLatch ready, CountDownLatch start, UUID owner,
                                      LedgerService.AccountView source, LedgerService.AccountView destination) {
        return () -> {
            ready.countDown();
            start.await();
            try {
                inTransaction(() -> ledger.internalTransfer(UUID.randomUUID(), source.accountId(), destination.accountNumber(), 8_000, owner, "concurrency-test"));
                return "POSTED";
            } catch (ApiException rejected) {
                assertTrue(rejected.status == 422);
                return rejected.code;
            }
        };
    }

    private LedgerService.AccountView open(UUID owner) {
        return inTransaction(() -> ledger.openAccount(UUID.randomUUID(), UUID.randomUUID(), owner, "concurrency-test"));
    }

    private <T> T inTransaction(java.util.function.Supplier<T> work) {
        return transaction.execute(status -> work.get());
    }
}
