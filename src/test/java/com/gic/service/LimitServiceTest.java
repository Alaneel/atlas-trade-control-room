package com.gic.service;

import com.gic.exception.BusinessException;
import com.gic.model.Limit;
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
        saveLimit("primary", 1_000.0);

        List<Boolean> outcomes = concurrently(32,
                () -> limitService.checkAndReserveLimit("TEST", "EQUITY", 75.0));

        assertEquals(13, outcomes.stream().filter(Boolean::booleanValue).count());
        assertEquals(25.0, limitService.getAvailableLimit("TEST", "EQUITY").getAvailableLimit());
    }

    @Test
    void concurrentReleasesDoNotLoseCredits() throws Exception {
        saveLimit("primary", 0.0);

        concurrently(64, () -> {
            limitService.releaseLimitReservation("TEST", "EQUITY", 10.0);
            return true;
        });

        assertEquals(640.0, limitService.getAvailableLimit("TEST", "EQUITY").getAvailableLimit());
    }

    @Test
    void reservationAndReleaseKeepExactBoundary() {
        saveLimit("primary", 100.0);
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", 100.0));
        assertFalse(limitService.checkAndReserveLimit("TEST", "EQUITY", 1.0));
        limitService.confirmLimitUsage("TEST", "EQUITY", 100.0);
        assertEquals(0.0, limitService.getAvailableLimit("TEST", "EQUITY").getAvailableLimit());
        limitService.releaseLimitReservation("TEST", "EQUITY", 100.0);
        assertEquals(100.0, limitService.getAvailableLimit("TEST", "EQUITY").getAvailableLimit());
    }

    @Test
    void duplicateSnapshotsDoNotGrantAdditionalCapacity() {
        saveLimit("primary", 20.0);
        saveLimit("secondary", 10_000.0);
        assertFalse(limitService.checkAndReserveLimit("TEST", "EQUITY", 30.0));
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", 20.0));
        assertFalse(limitService.checkAndReserveLimit("TEST", "EQUITY", 1.0));
        limitService.releaseLimitReservation("TEST", "EQUITY", 20.0);
        assertEquals(20.0, limitService.getAvailableLimit("TEST", "EQUITY").getAvailableLimit());
        assertEquals(10_000.0, mongoTemplate.findById("secondary", Limit.class).getAvailableLimit());
    }

    @Test
    void missingLimitCannotBeReservedOrCreatedByRelease() {
        assertFalse(limitService.checkAndReserveLimit("TEST", "EQUITY", 10.0));
        limitService.releaseLimitReservation("TEST", "EQUITY", 10.0);
        assertNull(limitService.getAvailableLimit("TEST", "EQUITY"));
    }

    @Test
    void legacyObjectIdLimitsSupportReservationAndRelease() {
        String legacyId = "507f1f77bcf86cd799439011";
        saveLimit(legacyId, 100.0);
        assertTrue(limitService.checkAndReserveLimit("TEST", "EQUITY", 40.0));
        assertEquals(60.0, mongoTemplate.findById(legacyId, Limit.class).getAvailableLimit());
        limitService.releaseLimitReservation("TEST", "EQUITY", 40.0);
        assertEquals(100.0, mongoTemplate.findById(legacyId, Limit.class).getAvailableLimit());
    }

    @ParameterizedTest
    @MethodSource("invalidAmounts")
    void invalidAmountsNeverChangeCapacity(Double amount) {
        saveLimit("primary", 100.0);
        BusinessException reservation = assertThrows(BusinessException.class,
                () -> limitService.checkAndReserveLimit("TEST", "EQUITY", amount));
        assertEquals("INVALID_TRADE_AMOUNT", reservation.getErrorCode());
        assertThrows(BusinessException.class,
                () -> limitService.releaseLimitReservation("TEST", "EQUITY", amount));
        assertEquals(100.0, limitService.getAvailableLimit("TEST", "EQUITY").getAvailableLimit());
    }

    static Stream<Double> invalidAmounts() {
        return Stream.of(null, 0.0, -1.0, Double.NaN,
                Double.POSITIVE_INFINITY, Double.NEGATIVE_INFINITY);
    }

    private void saveLimit(String id, double balance) {
        Limit limit = new Limit();
        limit.setId(id);
        limit.setCounterparty("TEST");
        limit.setInstrumentGroup("EQUITY");
        limit.setCurrency("USD");
        limit.setAvailableLimit(balance);
        mongoTemplate.save(limit);
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
