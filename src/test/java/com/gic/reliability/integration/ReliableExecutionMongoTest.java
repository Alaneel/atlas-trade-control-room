package com.gic.reliability.integration;

import com.gic.model.InstrumentVerificationRequest;
import com.gic.config.MockDataInitializer;
import com.gic.reliability.ExecutionReceipt;
import com.gic.reliability.ExecutionGateway;
import org.springframework.boot.CommandLineRunner;
import org.springframework.test.util.ReflectionTestUtils;
import com.gic.model.Limit;
import com.gic.model.TradeRequest;
import com.gic.reliability.MongoExecutionGateway;
import com.gic.reliability.ReliableTradeStore;
import com.gic.reliability.TradeOrder;
import com.gic.reliability.TradeWorker;
import com.gic.reliability.TransactionRunner;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.web.server.ResponseStatusException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.Optional;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Exercises actual Mongo transactions; no embedded database or in-memory ledger substitutes. */
@EnabledIfEnvironmentVariable(named = "ATLAS_MONGO_URI", matches = ".+")
class ReliableExecutionMongoTest {
    private static MongoAcceptanceSupport fixture;
    private static ReliableTradeStore store;

    @BeforeAll
    static void connect() {
        fixture = new MongoAcceptanceSupport();
        store = newStore(fixture.mongo);
    }

    @AfterAll
    static void removeOnlyOwnedDatabase() {
        if (fixture != null) fixture.close();
    }

    @Test
    void oneHundredConcurrentTenUnitTradesNeverOverspendOneHundred() throws Exception {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        TradeWorker worker = worker(store, "SUCCESS");
        List<String> ids = concurrent(100, 12, index -> {
            TradeOrder order = create(store, counterparty, "USD", "10.00", key());
            worker.dispatch(order.getId());
            return order.getId();
        });

        long executions = ids.stream().filter(id -> "EXECUTED".equals(status(id))).count();
        long rejections = ids.stream().filter(id -> "REJECTED".equals(status(id))).count();
        assertThat(executions).isEqualTo(10);
        assertThat(rejections).isEqualTo(90);
        assertLedger(counterparty, "USD", "0.00", "0.00", "100.00");
        assertThat(receipts(ids)).isEqualTo(10);
    }

    @Test
    void oneHundredConcurrentReplaysProduceOneDurableTradeAndExecution() throws Exception {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        String idempotencyKey = key();
        TradeWorker worker = worker(store, "SUCCESS");
        List<String> ids = concurrent(100, 12, index -> {
            TradeOrder order = create(store, counterparty, "USD", "10.00", idempotencyKey);
            worker.dispatch(order.getId());
            return order.getId();
        });
        assertThat(new HashSet<>(ids)).hasSize(1);
        worker.dispatch(ids.get(0));
        assertThat(status(ids.get(0))).isEqualTo("EXECUTED");
        assertThat(fixture.mongo.count(Query.query(Criteria.where("counterparty").is(counterparty)), "reliable_trades"))
                .isEqualTo(1);
        assertThat(receipts(ids)).isEqualTo(1);
        assertLedger(counterparty, "USD", "90.00", "0.00", "10.00");
    }

    @Test
    void reusingAKeyWithAnotherPayloadConflictsWithoutMovingMoney() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        String idempotencyKey = key();
        TradeOrder original = create(store, counterparty, "USD", "10.00", idempotencyKey);
        assertThatThrownBy(() -> create(store, counterparty, "USD", "11.00", idempotencyKey))
                .isInstanceOfSatisfying(ResponseStatusException.class,
                        exception -> assertThat(exception.getStatus().value()).isEqualTo(409));
        assertThat(status(original.getId())).isEqualTo("RESERVED");
        assertLedger(counterparty, "USD", "90.00", "10.00", "0.00");
    }

    @Test
    void definitiveRejectionReleasesExactlyOnceDespiteRepeatedReconciliation() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        TradeOrder order = create(store, counterparty, "USD", "10.00", key());
        TradeWorker worker = worker(store, "REJECT");
        for (int index = 0; index < 20; index++) worker.dispatch(order.getId());
        assertThat(status(order.getId())).isEqualTo("REJECTED");
        assertLedger(counterparty, "USD", "100.00", "0.00", "0.00");
        assertThat(receipts(List.of(order.getId()))).isEqualTo(1);
    }

    @Test
    void lostSuccessResponseHoldsFundsThenReconcilesPersistedReceiptOnce() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        TradeOrder order = create(store, counterparty, "USD", "10.00", key());
        worker(store, "TIMEOUT_AFTER_SUCCESS").dispatch(order.getId());
        assertThat(status(order.getId())).isEqualTo("UNKNOWN");
        assertLedger(counterparty, "USD", "90.00", "10.00", "0.00");
        assertThat(receipts(List.of(order.getId()))).isEqualTo(1);

        // Recreate application services; only Mongo carries state across this boundary.
        ReliableTradeStore recreatedStore = newStore(fixture.mongo);
        MongoExecutionGateway persistedVenue = new MongoExecutionGateway(fixture.mongo, "SUCCESS");
        ExecutionGateway readOnlyGateway = new ExecutionGateway() {
            @Override public ExecutionReceipt submit(TradeOrder trade) {
                throw new AssertionError("Manual reconciliation must never resubmit");
            }
            @Override public Optional<ExecutionReceipt> query(String id) { return persistedVenue.query(id); }
        };
        TradeWorker restarted = new TradeWorker(recreatedStore, readOnlyGateway, 5000L);
        for (int index = 0; index < 20; index++) restarted.reconcile(order.getId());
        assertThat(status(order.getId())).isEqualTo("EXECUTED");
        assertLedger(counterparty, "USD", "90.00", "0.00", "10.00");
        assertThat(receipts(List.of(order.getId()))).isEqualTo(1);
    }

    @Test
    void timeoutBeforeSubmissionDoesNotReleaseFundsAndStableKeyRetryRecovers() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        TradeOrder order = create(store, counterparty, "USD", "10.00", key());
        worker(store, "TIMEOUT_BEFORE_SUBMIT").dispatch(order.getId());
        assertThat(status(order.getId())).isEqualTo("UNKNOWN");
        assertThat(receipts(List.of(order.getId()))).isZero();
        assertLedger(counterparty, "USD", "90.00", "10.00", "0.00");
        makeDue(order.getId());
        worker(newStore(fixture.mongo), "SUCCESS").dispatch(order.getId());
        assertThat(status(order.getId())).isEqualTo("EXECUTED");
        assertLedger(counterparty, "USD", "90.00", "0.00", "10.00");
    }

    @Test
    void committedReservationSurvivesServiceRecreationBeforeDispatch() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        TradeOrder order = create(store, counterparty, "USD", "10.00", key());
        assertThat(status(order.getId())).isEqualTo("RESERVED");
        worker(newStore(fixture.mongo), "SUCCESS").dispatch(order.getId());
        assertThat(status(order.getId())).isEqualTo("EXECUTED");
        assertLedger(counterparty, "USD", "90.00", "0.00", "10.00");
    }

    @Test
    void venueSuccessSurvivesDatabaseSettlementFailureAndAnExpiredWorkerLease() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        TradeOrder order = create(store, counterparty, "USD", "10.00", key());
        ReliableTradeStore failing = new ReliableTradeStore(fixture.mongo, new TransactionRunner(fixture.transactions)) {
            @Override public TradeOrder settle(TradeOrder claim, ExecutionReceipt receipt) {
                throw new IllegalStateException("injected settlement outage");
            }
        };
        assertThatThrownBy(() -> worker(failing, "SUCCESS").dispatch(order.getId()))
                .hasMessageContaining("injected settlement outage");
        assertThat(status(order.getId())).isEqualTo("SUBMITTED");
        assertThat(receipts(List.of(order.getId()))).isEqualTo(1);
        assertLedger(counterparty, "USD", "90.00", "10.00", "0.00");
        makeDue(order.getId());
        worker(newStore(fixture.mongo), "SUCCESS").dispatch(order.getId());
        assertThat(status(order.getId())).isEqualTo("EXECUTED");
        assertThat(receipts(List.of(order.getId()))).isEqualTo(1);
        assertLedger(counterparty, "USD", "90.00", "0.00", "10.00");
    }

    @Test
    void expiredLeaseHolderCannotSettleOverANewerClaim() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        TradeOrder order = create(store, counterparty, "USD", "10.00", key());
        TradeOrder oldClaim = store.claim(order.getId(), 5000L);
        makeDue(order.getId());
        TradeOrder newClaim = store.claim(order.getId(), 5000L);
        assertThat(newClaim.getLeaseToken()).isNotEqualTo(oldClaim.getLeaseToken());
        MongoExecutionGateway gateway = new MongoExecutionGateway(fixture.mongo, "SUCCESS");
        ExecutionReceipt receipt = gateway.submit(newClaim);
        store.settle(oldClaim, receipt);
        assertThat(status(order.getId())).isEqualTo("SUBMITTED");
        assertLedger(counterparty, "USD", "90.00", "10.00", "0.00");
        store.settle(newClaim, receipt);
        store.settle(oldClaim, receipt);
        assertThat(status(order.getId())).isEqualTo("EXECUTED");
        assertLedger(counterparty, "USD", "90.00", "0.00", "10.00");
    }

    @Test
    void repeatedDevelopmentSeedDoesNotDuplicateRecordsOrResetConsumedMoney() throws Exception {
        MockDataInitializer initializer = new MockDataInitializer(fixture.mongo);
        CommandLineRunner seed = ReflectionTestUtils.invokeMethod(initializer, "initDatabase");
        assertThat(seed).isNotNull();
        seed.run();
        long instruments = fixture.mongo.getCollection("instruments").countDocuments();
        long limits = fixture.mongo.getCollection("limits").countDocuments();
        TradeOrder order = create(store, "NORTHSTAR", "USD", "10.00", key());
        worker(store, "SUCCESS").dispatch(order.getId());
        seed.run();
        seed.run();
        assertThat(fixture.mongo.getCollection("instruments").countDocuments()).isEqualTo(instruments);
        assertThat(fixture.mongo.getCollection("limits").countDocuments()).isEqualTo(limits);
        assertLedger("NORTHSTAR", "USD", "90.00", "0.00", "10.00");
    }

    @Test
    void legacyFloatingPointLedgerFailsClosedWithoutImportingFixtures() {
        try (MongoAcceptanceSupport isolated = new MongoAcceptanceSupport()) {
            isolated.mongo.getCollection("limits").insertOne(new Document("counterparty", "legacy")
                    .append("instrumentGroup", "EQUITY").append("currency", "USD").append("availableLimit", 100.0));
            MockDataInitializer initializer = new MockDataInitializer(isolated.mongo);
            CommandLineRunner seed = ReflectionTestUtils.invokeMethod(initializer, "initDatabase");
            assertThat(seed).isNotNull();
            assertThatThrownBy(() -> seed.run()).hasMessageContaining("Legacy limits detected");
            assertThat(isolated.mongo.getCollection("limits").countDocuments()).isEqualTo(1);
            assertThat(isolated.mongo.getCollection("instruments").countDocuments()).isZero();
        }
    }

    @Test
    void currencyIsPartOfTheReservationScope() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        seed(counterparty, "SGD", "200.00");
        TradeWorker worker = worker(store, "SUCCESS");
        worker.dispatch(create(store, counterparty, "USD", "10.00", key()).getId());
        worker.dispatch(create(store, counterparty, "SGD", "20.00", key()).getId());
        assertLedger(counterparty, "USD", "90.00", "0.00", "10.00");
        assertLedger(counterparty, "SGD", "180.00", "0.00", "20.00");
    }

    @Test
    void decimalCentsStayExactAndAreStoredAsDecimal128() {
        String counterparty = scope();
        seed(counterparty, "USD", "0.30");
        TradeWorker worker = worker(store, "SUCCESS");
        for (int index = 0; index < 3; index++) {
            worker.dispatch(create(store, counterparty, "USD", "0.10", key()).getId());
        }
        TradeOrder rejected = create(store, counterparty, "USD", "0.01", key());
        assertThat(status(rejected.getId())).isEqualTo("REJECTED");
        assertLedger(counterparty, "USD", "0.00", "0.00", "0.30");
        Document raw = fixture.mongo.getCollection("limits").find(new Document("counterparty", counterparty)).first();
        assertThat(raw).isNotNull();
        assertThat(raw.get("usedLimit")).isInstanceOf(org.bson.types.Decimal128.class);
        assertThat(raw.get("availableLimit")).isInstanceOf(org.bson.types.Decimal128.class);
    }

    @Test
    void eventWriteFailureAbortsReservationAndTradeTogether() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        MongoTemplate failing = new MongoTemplate(fixture.factory) {
            @Override
            public <T> T insert(T value) {
                rejectEvent(getCollectionName(value.getClass()));
                return super.insert(value);
            }
            @Override
            public <T> T insert(T value, String collectionName) {
                rejectEvent(collectionName);
                return super.insert(value, collectionName);
            }
            private void rejectEvent(String collectionName) {
                if ("trade_events".equals(collectionName)) throw new IllegalStateException("injected event-write failure");
            }
        };
        assertThatThrownBy(() -> create(newStore(failing), counterparty, "USD", "10.00", key()))
                .hasMessageContaining("injected event-write failure");
        assertLedger(counterparty, "USD", "100.00", "0.00", "0.00");
        assertThat(fixture.mongo.count(Query.query(Criteria.where("counterparty").is(counterparty)), "reliable_trades"))
                .isZero();
    }

    @Test
    void eventsHaveUniqueOrderedSequencesAndTerminalReplayAppendsNothing() {
        String counterparty = scope();
        seed(counterparty, "USD", "100.00");
        TradeOrder order = create(store, counterparty, "USD", "10.00", key());
        TradeWorker worker = worker(store, "SUCCESS");
        worker.dispatch(order.getId());
        List<Document> before = eventDocuments(order.getId());
        for (int index = 0; index < 20; index++) worker.dispatch(order.getId());
        assertThat(eventDocuments(order.getId())).isEqualTo(before);
        assertThat(before.size()).isGreaterThanOrEqualTo(3);
        List<Long> sequences = before.stream().map(event -> ((Number) event.get("sequence")).longValue())
                .collect(Collectors.toList());
        assertThat(new HashSet<>(sequences)).hasSize(sequences.size());
        assertThat(sequences).isSorted();
        assertThat(before.get(before.size() - 1).getString("status")).isEqualTo("EXECUTED");
    }

    static ReliableTradeStore newStore(MongoTemplate mongo) {
        ReliableTradeStore result = new ReliableTradeStore(mongo, new TransactionRunner(fixture.transactions));
        result.initialize();
        return result;
    }

    static TradeWorker worker(ReliableTradeStore targetStore, String scenario) {
        return new TradeWorker(targetStore, new MongoExecutionGateway(fixture.mongo, scenario), 5000L);
    }

    static TradeOrder create(ReliableTradeStore targetStore, String counterparty, String currency,
                             String amount, String idempotencyKey) {
        return targetStore.create(TradeOrder.proposal(idempotencyKey, request(counterparty, currency, amount),
                "EQUITY", currency, null));
    }

    static TradeRequest request(String counterparty, String currency, String amount) {
        InstrumentVerificationRequest instrument = new InstrumentVerificationRequest();
        instrument.setInstrumentGroup("EQUITY");
        instrument.setInstrument("ACCEPTANCE");
        instrument.setTradeCurrency(currency);
        instrument.setSettlementCurrency(currency);
        instrument.setCountry("US");
        instrument.setExchange("TEST");
        instrument.setDepartment("TEST");
        TradeRequest request = new TradeRequest();
        request.setInstrumentVerificationRequest(instrument);
        request.setCounterparty(counterparty);
        request.setAmount(new BigDecimal(amount));
        request.setConfirmed(true);
        return request;
    }

    static void seed(String counterparty, String currency, String total) {
        Limit limit = new Limit();
        limit.setId(UUID.randomUUID().toString());
        limit.setCounterparty(counterparty);
        limit.setInstrumentGroup("EQUITY");
        limit.setCurrency(currency);
        limit.setTotalLimit(new BigDecimal(total));
        limit.setAvailableLimit(new BigDecimal(total));
        limit.setReservedLimit(new BigDecimal("0.00"));
        limit.setUsedLimit(new BigDecimal("0.00"));
        limit.setDataDate(LocalDate.now());
        fixture.mongo.insert(limit);
    }

    static void assertLedger(String counterparty, String currency, String available, String reserved, String used) {
        Limit limit = fixture.mongo.findOne(Query.query(Criteria.where("counterparty").is(counterparty)
                .and("instrumentGroup").is("EQUITY").and("currency").is(currency)), Limit.class);
        assertThat(limit).isNotNull();
        assertThat(limit.getAvailableLimit()).isEqualByComparingTo(available);
        assertThat(limit.getReservedLimit()).isEqualByComparingTo(reserved);
        assertThat(limit.getUsedLimit()).isEqualByComparingTo(used);
        assertThat(limit.getAvailableLimit().add(limit.getReservedLimit()).add(limit.getUsedLimit()))
                .isEqualByComparingTo(limit.getTotalLimit());
        assertThat(limit.getAvailableLimit()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(limit.getReservedLimit()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
        assertThat(limit.getUsedLimit()).isGreaterThanOrEqualTo(BigDecimal.ZERO);
    }

    static String status(String id) {
        Document document = fixture.mongo.getCollection("reliable_trades").find(new Document("_id", id)).first();
        assertThat(document).isNotNull();
        return document.getString("status");
    }

    static long receipts(List<String> ids) {
        return fixture.mongo.count(Query.query(Criteria.where("_id").in(new HashSet<>(ids))),
                "simulated_execution_receipts");
    }

    private static List<Document> eventDocuments(String id) {
        return fixture.mongo.getCollection("trade_events").find(new Document("tradeId", id))
                .sort(new Document("sequence", 1)).into(new ArrayList<>());
    }

    static void makeDue(String id) {
        fixture.mongo.updateFirst(Query.query(Criteria.where("_id").is(id)),
                new Update().set("leaseUntil", Instant.EPOCH).set("nextAttemptAt", Instant.EPOCH), "reliable_trades");
    }

    static String scope() { return "acceptance-" + UUID.randomUUID(); }
    static String key() { return "acceptance-" + UUID.randomUUID(); }

    interface IndexedOperation<T> { T run(int index) throws Exception; }

    static <T> List<T> concurrent(int count, int threads, IndexedOperation<T> operation) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int index = 0; index < count; index++) {
                final int current = index;
                futures.add(pool.submit((Callable<T>) () -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("start latch timed out");
                    return operation.run(current);
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) results.add(future.get(120, TimeUnit.SECONDS));
            return results;
        } finally {
            start.countDown();
            pool.shutdownNow();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("worker pool did not stop");
        }
    }
}
