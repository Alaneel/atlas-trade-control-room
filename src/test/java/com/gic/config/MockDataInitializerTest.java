package com.gic.config;

import com.gic.model.Instrument;
import com.gic.model.Limit;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.test.context.ActiveProfiles;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.assertEquals;

@DataMongoTest
@ActiveProfiles("test")
class MockDataInitializerTest {
    @Autowired private MongoTemplate mongo;
    private MockDataInitializer initializer;

    @BeforeEach
    void setUp() {
        mongo.remove(new Query(), Limit.class);
        mongo.remove(new Query(), Instrument.class);
        initializer = new MockDataInitializer(mongo);
    }

    @Test
    void restartDoesNotRestoreConsumedCapacity() {
        initializer.seedLimit(limit());
        mongo.updateFirst(new Query(), new Update().inc("availableLimit", -75.0), Limit.class);
        initializer.seedLimit(limit());
        assertEquals(1, mongo.count(new Query(), Limit.class));
        assertEquals(25.0, mongo.findOne(new Query(), Limit.class).getAvailableLimit());
    }

    @Test
    void legacyLimitIsPreservedInsteadOfAddingFreshCapacity() {
        Limit legacy = limit();
        legacy.setId("legacy-snapshot");
        legacy.setAvailableLimit(10.0);
        mongo.save(legacy);
        initializer.seedLimit(limit());
        assertEquals(1, mongo.count(new Query(), Limit.class));
        assertEquals(10.0, mongo.findById("legacy-snapshot", Limit.class).getAvailableLimit());
    }

    @Test
    void instrumentSeedingIsIdempotentAndDistinguishesDepartments() {
        initializer.seedInstrument(instrument("RISK"));
        initializer.seedInstrument(instrument("RISK"));
        initializer.seedInstrument(instrument("TRADING"));
        assertEquals(2, mongo.count(new Query(), Instrument.class));
    }

    @Test
    void legacyInstrumentIsNotDuplicated() {
        Instrument legacy = instrument("RISK");
        legacy.setId("legacy-instrument");
        mongo.save(legacy);
        initializer.seedInstrument(instrument("RISK"));
        assertEquals(1, mongo.count(new Query(), Instrument.class));
    }

    @Test
    void concurrentStartsInsertOneLimitPool() throws Exception {
        ExecutorService threads = Executors.newFixedThreadPool(12);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<?>> results = new ArrayList<>();
        try {
            for (int i = 0; i < 12; i++) {
                results.add(threads.submit(() -> {
                    if (!start.await(10, TimeUnit.SECONDS)) {
                        throw new IllegalStateException("Seed workers did not start");
                    }
                    initializer.seedLimit(limit());
                    return null;
                }));
            }
            start.countDown();
            for (Future<?> result : results) {
                result.get(20, TimeUnit.SECONDS);
            }
        } finally {
            threads.shutdownNow();
            threads.awaitTermination(10, TimeUnit.SECONDS);
        }
        Query pair = Query.query(Criteria.where("counterparty").is("TEST").and("instrumentGroup").is("Bonds"));
        assertEquals(1, mongo.count(pair, Limit.class));
        assertEquals(100.0, mongo.findOne(pair, Limit.class).getAvailableLimit());
    }

    private Limit limit() {
        Limit limit = new Limit();
        limit.setCounterparty("TEST");
        limit.setInstrumentGroup("Bonds");
        limit.setCurrency("USD");
        limit.setDataDate(LocalDate.of(2024, 9, 5));
        limit.setAvailableLimit(100.0);
        return limit;
    }

    private Instrument instrument(String department) {
        Instrument instrument = new Instrument();
        instrument.setInstrumentGroup("Bonds");
        instrument.setInstrument("Sample bond");
        instrument.setDepartment(department);
        instrument.setRiskCountry("US");
        instrument.setExchange("NYSE");
        instrument.setTradeCCY("USD");
        instrument.setSettlementCCY("USD");
        return instrument;
    }
}
