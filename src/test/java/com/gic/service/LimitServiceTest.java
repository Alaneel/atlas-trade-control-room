package com.gic.service;

import com.gic.exception.BusinessException;
import com.gic.model.Limit;
import com.gic.model.TradeHistory;
import com.gic.model.TradeRequest;
import com.gic.validation.MoneyAmounts;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.test.context.ActiveProfiles;

import java.util.ArrayList;
import java.math.BigDecimal;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

@DataMongoTest
@Import(LimitService.class)
@ActiveProfiles("test")
class LimitServiceTest {

    @Autowired
    private LimitService limitService;

    @Autowired
    private MongoTemplate mongoTemplate;

    @BeforeEach
    void clearLimits() {
        mongoTemplate.remove(new Query(), Limit.class);
    }

    @Test
    void concurrentReservationsCannotExceedCapacity() throws Exception {
        saveLimit("primary", "1000");

        List<Boolean> outcomes = concurrently(32,
                () -> limitService.checkAndReserveLimit("TEST", "EQUITY", money("75")));

        assertEquals(13, outcomes.stream().filter(Boolean::booleanValue).count());
        assertBalance("25");
    }

    @Test
    void concurrentReleasesDoNotLoseCredits() throws Exception {
        saveLimit("primary", "0");

        concurrently(64, () -> {
            limitService.releaseLimitReservation("TEST", "EQUITY", money("10"));
            return true;
        });

        assertBalance("640");
    }

    @Test
    void reservationAndReleaseKeepExactBoundary() {
        saveLimit("primary", "100");
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", money("100")));
        assertFalse(limitService.checkAndReserveLimit("TEST", "EQUITY", money("1")));
        limitService.confirmLimitUsage("TEST", "EQUITY", money("100"));
        assertBalance("0");
        limitService.releaseLimitReservation("TEST", "EQUITY", money("100"));
        assertBalance("100");
    }

    @Test
    void duplicateSnapshotsDoNotGrantAdditionalCapacity() {
        saveLimit("primary", "20");
        saveLimit("secondary", "10000");
        assertFalse(limitService.checkAndReserveLimit("TEST", "EQUITY", money("30")));
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", money("20")));
        assertFalse(limitService.checkAndReserveLimit("TEST", "EQUITY", money("1")));
        limitService.releaseLimitReservation("TEST", "EQUITY", money("20"));
        assertBalance("20");
        assertEquals(0, money("10000").compareTo(mongoTemplate.findById("secondary", Limit.class).getAvailableLimit()));
    }

    @Test
    void missingLimitCannotBeReservedOrCreatedByRelease() {
        assertFalse(limitService.checkAndReserveLimit("TEST", "EQUITY", money("10")));
        limitService.releaseLimitReservation("TEST", "EQUITY", money("10"));
        assertNull(limitService.getAvailableLimit("TEST", "EQUITY"));
    }

    @Test
    void legacyObjectIdLimitsSupportReservationAndRelease() {
        String legacyId = "507f1f77bcf86cd799439011";
        saveLimit(legacyId, "100");
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", money("40")));
        assertBalance("60");
        limitService.releaseLimitReservation("TEST", "EQUITY", money("40"));
        assertBalance("100");
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void invalidAmountsNeverChangeCapacity(BigDecimal amount) {
        saveLimit("primary", "100");
        BusinessException reservation = assertThrows(BusinessException.class,
                () -> limitService.checkAndReserveLimit("TEST", "EQUITY", amount));
        assertEquals("INVALID_TRADE_AMOUNT", reservation.getErrorCode());
        assertThrows(BusinessException.class,
                () -> limitService.releaseLimitReservation("TEST", "EQUITY", amount));
        assertBalance("100");
    }

    static Stream<BigDecimal> invalidAmounts() {
        return Stream.of(null, money("0"), money("-1"), money("0.000000001"),
                money("100000000000000000000000000"), money("100.000000001"));
    }

    private void saveLimit(String id, String balance) {
        Limit limit = new Limit();
        limit.setId(id);
        limit.setCounterparty("TEST");
        limit.setInstrumentGroup("EQUITY");
        limit.setCurrency("USD");
        limit.setAvailableLimit(money(balance));
        mongoTemplate.save(limit);
    }

    @Test
    void fractionalDebitsAndCreditsAreExactDecimal128() {
        saveLimit("primary", "0.3");
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", money("0.1")));
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", money("0.2")));
        assertBalance("0");
        assertFalse(limitService.checkAndReserveLimit("TEST", "EQUITY", money("0.00000001")));
        limitService.releaseLimitReservation("TEST", "EQUITY", money("0.1"));
        limitService.releaseLimitReservation("TEST", "EQUITY", money("0.2"));
        assertBalance("0.3");
        assertTrue(rawLimit().get("availableLimit") instanceof Decimal128);
        assertEquals(0, money("0.3").compareTo(((Decimal128) rawLimit().get("availableLimit")).bigDecimalValue()));
    }

    @Test
    void legacyDoubleBalanceMigratesBeforeDecimalDebit() {
        saveLegacyBalance(0.3);
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", money("0.1")));
        assertBalance("0.2");
        assertTrue(rawLimit().get("availableLimit") instanceof Decimal128);
    }

    @Test
    void migrationCannotOverwriteConcurrentReservations() throws Exception {
        saveLegacyBalance(1000.0);
        List<Boolean> outcomes = concurrently(32,
                () -> limitService.checkAndReserveLimit("TEST", "EQUITY", money("75")));
        assertEquals(13, outcomes.stream().filter(Boolean::booleanValue).count());
        assertBalance("25");
        assertTrue(rawLimit().get("availableLimit") instanceof Decimal128);
    }

    @ParameterizedTest
    @MethodSource("invalidLegacyBalances")
    void unsafeLegacyBalancesFailClosedWithoutRounding(Object legacy) {
        saveLegacyBalance(legacy);
        BusinessException error = assertThrows(BusinessException.class,
                () -> limitService.checkAndReserveLimit("TEST", "EQUITY", money("0.1")));
        assertEquals("INVALID_LIMIT_BALANCE", error.getErrorCode());
        assertEquals(legacy, rawLimit().get("availableLimit"));
    }

    static Stream<Object> invalidLegacyBalances() {
        return Stream.of(0.1 + 0.2, Double.NaN, Double.POSITIVE_INFINITY,
                Double.NEGATIVE_INFINITY, -1.0, Double.MAX_VALUE, "100");
    }

    @Test
    void supportedPrecisionBoundaryNeverRoundsOrOverflows() {
        saveLimit("primary", MoneyAmounts.MAX_VALUE.toPlainString());
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", money("0.00000001")));
        limitService.releaseLimitReservation("TEST", "EQUITY", money("0.00000001"));
        assertBalance(MoneyAmounts.MAX_VALUE.toPlainString());
        assertThrows(BusinessException.class,
                () -> limitService.releaseLimitReservation("TEST", "EQUITY", money("0.00000001")));
        assertBalance(MoneyAmounts.MAX_VALUE.toPlainString());
        assertEquals(money("0.1"), MoneyAmounts.requirePositive(money("0.100000000000")));
    }

    @Test
    void historyAndRequestSnapshotStoreDecimal128() {
        TradeHistory history = new TradeHistory();
        history.setAmount(money("0.12345678"));
        Document historyBson = new Document();
        mongoTemplate.getConverter().write(history, historyBson);
        assertTrue(historyBson.get("amount") instanceof Decimal128);
        TradeRequest request = new TradeRequest();
        request.setAmount(money("0.12345678"));
        Document snapshotBson = new Document();
        mongoTemplate.getConverter().write(request, snapshotBson);
        assertTrue(snapshotBson.get("amount") instanceof Decimal128);
    }

    private void saveLegacyBalance(Object balance) {
        mongoTemplate.getCollection("limits").insertOne(new Document("_id", "primary")
                .append("counterparty", "TEST").append("instrumentGroup", "EQUITY")
                .append("currency", "USD").append("availableLimit", balance));
    }

    private Document rawLimit() {
        return mongoTemplate.getCollection("limits").find(new Document("_id", "primary")).first();
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }

    private void assertBalance(String expected) {
        assertEquals(0, money(expected).compareTo(limitService.getAvailableLimit("TEST", "EQUITY").getAvailableLimit()));
    }

    private <T> List<T> concurrently(int count, Callable<T> operation) throws Exception {
        ExecutorService workers = Executors.newFixedThreadPool(count);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<T>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                futures.add(workers.submit(() -> {
                    assertTrue(start.await(10, TimeUnit.SECONDS));
                    return operation.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(20, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            workers.shutdownNow();
            assertTrue(workers.awaitTermination(10, TimeUnit.SECONDS));
        }
    }
}
