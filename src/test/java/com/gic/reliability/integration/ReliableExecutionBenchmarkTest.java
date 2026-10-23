package com.gic.reliability.integration;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.gic.model.Limit;
import com.gic.reliability.MongoExecutionGateway;
import com.gic.reliability.ReliableTradeStore;
import com.gic.reliability.TradeOrder;
import com.gic.reliability.TradeWorker;
import com.gic.reliability.TransactionRunner;
import org.bson.Document;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/** Optional measurement of the durable Java service path, excluding HTTP and any real venue. */
@EnabledIfEnvironmentVariable(named = "ATLAS_MONGO_URI", matches = ".+")
@EnabledIfSystemProperty(named = "atlas.benchmark", matches = "true")
class ReliableExecutionBenchmarkTest {
    private static final long SEED = 20261020L;
    private static final BigDecimal AMOUNT = new BigDecimal("0.01");
    private static final BigDecimal TOTAL = new BigDecimal("100000.00");

    @Test
    void measureHotAndDistributedAccountsWithRetainedSamples() throws Exception {
        int requests = boundedProperty("atlas.benchmark.requests", 500, 10, 10000);
        int threads = boundedProperty("atlas.benchmark.threads", 8, 1, 64);
        Map<String, Object> report = new LinkedHashMap<>();
        report.put("schema_version", 1);
        report.put("recorded_at", Instant.now().toString());
        report.put("measurement", "closed-loop durable Java service path with real Mongo transactions and persisted simulated execution receipts");
        report.put("exclusions", List.of("HTTP transport", "real external venue", "production capacity or SLA", "independent multi-node failure tolerance"));
        report.put("seed", SEED);
        report.put("java_version", System.getProperty("java.version"));
        report.put("java_vm", System.getProperty("java.vm.name"));
        report.put("os", System.getProperty("os.name"));
        report.put("os_arch", System.getProperty("os.arch"));
        report.put("available_processors", Runtime.getRuntime().availableProcessors());
        report.put("requests_per_workload", requests);
        report.put("client_threads", threads);
        report.put("amount", AMOUNT.toPlainString());
        report.put("retry_note", "Per-workload counters count actual transaction callback attempts, retries and exhausted retry budgets; errors remain visible. These do not count external venue attempts.");
        List<Map<String, Object>> workloads = new ArrayList<>();
        report.put("workloads", workloads);
        int errors;
        try (MongoAcceptanceSupport fixture = new MongoAcceptanceSupport()) {
            report.put("mongo_version", fixture.mongo.getDb().runCommand(new Document("buildInfo", 1)).getString("version"));
            report.put("mongo_topology", "caller-provided loopback replica set; see retained execution environment record");
            TransactionRunner transactions = new TransactionRunner(fixture.transactions);
            ReliableTradeStore store = new ReliableTradeStore(fixture.mongo, transactions);
            store.initialize();
            TradeWorker worker = new TradeWorker(store, new MongoExecutionGateway(fixture.mongo, "SUCCESS"), 5000L);
            Map<String, Object> warmup = workload(fixture, store, worker, transactions, 1, Math.min(50, requests), threads, "warmup");
            report.put("warmup_requests", Math.min(50, requests));
            report.put("warmup_errors", warmup.get("errors"));
            workloads.add(workload(fixture, store, worker, transactions, 1, requests, threads, "single_hot_account"));
            workloads.add(workload(fixture, store, worker, transactions, 64, requests, threads, "64_accounts"));
            errors = ((Number) warmup.get("errors")).intValue()
                    + workloads.stream().mapToInt(workload -> ((Number) workload.get("errors")).intValue()).sum();
        }
        report.put("owned_database_cleanup_succeeded", true);
        Path output = Path.of("target", "atlas-benchmark.json");
        Files.createDirectories(output.getParent());
        new ObjectMapper().enable(SerializationFeature.INDENT_OUTPUT).writeValue(output.toFile(), report);
        assertThat(errors).as("Every attempted request must be accounted for; inspect target/atlas-benchmark.json").isZero();
        for (Map<String, Object> workload : workloads) assertThat(workload.get("ledger_invariant_passed")).isEqualTo(true);
    }

    private Map<String, Object> workload(MongoAcceptanceSupport fixture, ReliableTradeStore store, TradeWorker worker,
                                         TransactionRunner transactions, int accounts, int requests, int threads, String name) throws Exception {
        long attemptsBefore = transactions.getAttemptCount();
        long retriesBefore = transactions.getRetryCount();
        long exhaustionsBefore = transactions.getExhaustionCount();
        long unknownBefore = transactions.getUnknownCommitCount();
        List<String> counterparties = new ArrayList<>();
        String runId = UUID.randomUUID().toString();
        for (int index = 0; index < accounts; index++) {
            String counterparty = "benchmark-" + runId + "-" + index;
            counterparties.add(counterparty);
            Limit limit = new Limit();
            limit.setId(UUID.randomUUID().toString());
            limit.setCounterparty(counterparty);
            limit.setInstrumentGroup("EQUITY");
            limit.setCurrency("USD");
            limit.setTotalLimit(TOTAL);
            limit.setAvailableLimit(TOTAL);
            limit.setReservedLimit(BigDecimal.ZERO.setScale(2));
            limit.setUsedLimit(BigDecimal.ZERO.setScale(2));
            limit.setDataDate(LocalDate.now());
            fixture.mongo.insert(limit);
        }
        List<Integer> assignment = new ArrayList<>();
        for (int index = 0; index < requests; index++) assignment.add(index % accounts);
        Collections.shuffle(assignment, new Random(SEED));
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<Map<String, Object>>> futures = new ArrayList<>();
        List<Map<String, Object>> samples = new ArrayList<>();
        long startNanos;
        long elapsed;
        try {
            for (int index = 0; index < requests; index++) {
                final int requestIndex = index;
                final String counterparty = counterparties.get(assignment.get(index));
                futures.add(pool.submit((Callable<Map<String, Object>>) () -> {
                    if (!start.await(10, TimeUnit.SECONDS)) throw new IllegalStateException("benchmark start timed out");
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("index", requestIndex);
                    sample.put("account_index", assignment.get(requestIndex));
                    long begin = System.nanoTime();
                    try {
                        TradeOrder order = store.create(TradeOrder.proposal("benchmark-" + runId + "-" + requestIndex,
                                ReliableExecutionMongoTest.request(counterparty, "USD", AMOUNT.toPlainString()), "EQUITY", "USD", null));
                        worker.dispatch(order.getId());
                        Document durable = fixture.mongo.getCollection("reliable_trades").find(new Document("_id", order.getId())).first();
                        String status = durable == null ? "MISSING" : durable.getString("status");
                        sample.put("status", status);
                        sample.put("error", !"EXECUTED".equals(status));
                    } catch (Exception exception) {
                        sample.put("status", "ERROR");
                        sample.put("error", true);
                        sample.put("error_type", exception.getClass().getName());
                        if (exception instanceof org.springframework.web.server.ResponseStatusException) {
                            org.springframework.web.server.ResponseStatusException response =
                                    (org.springframework.web.server.ResponseStatusException) exception;
                            sample.put("error_http_status", response.getStatus().value());
                            sample.put("error_reason", response.getReason());
                        }
                    }
                    sample.put("latency_ms", (System.nanoTime() - begin) / 1_000_000.0);
                    return sample;
                }));
            }
            startNanos = System.nanoTime();
            start.countDown();
            for (Future<Map<String, Object>> future : futures) samples.add(future.get(180, TimeUnit.SECONDS));
            elapsed = System.nanoTime() - startNanos;
        } finally {
            start.countDown();
            pool.shutdownNow();
            if (!pool.awaitTermination(10, TimeUnit.SECONDS)) throw new IllegalStateException("benchmark workers did not stop");
        }
        List<Double> latency = new ArrayList<>();
        Map<String, Integer> statuses = new TreeMap<>();
        int errors = 0;
        for (Map<String, Object> sample : samples) {
            latency.add(((Number) sample.get("latency_ms")).doubleValue());
            statuses.merge((String) sample.get("status"), 1, Integer::sum);
            if (Boolean.TRUE.equals(sample.get("error"))) errors++;
        }
        Collections.sort(latency);
        boolean invariant = true;
        BigDecimal used = BigDecimal.ZERO;
        BigDecimal reserved = BigDecimal.ZERO;
        for (String counterparty : counterparties) {
            Limit limit = fixture.mongo.findOne(Query.query(Criteria.where("counterparty").is(counterparty)), Limit.class);
            invariant &= limit != null && limit.getAvailableLimit().signum() >= 0 && limit.getReservedLimit().signum() >= 0
                    && limit.getUsedLimit().signum() >= 0 && limit.getTotalLimit().compareTo(
                        limit.getAvailableLimit().add(limit.getReservedLimit()).add(limit.getUsedLimit())) == 0;
            if (limit != null) {
                used = used.add(limit.getUsedLimit());
                reserved = reserved.add(limit.getReservedLimit());
            }
        }
        long executions = fixture.mongo.count(Query.query(Criteria.where("counterparty").in(counterparties)
                .and("status").is("EXECUTED")), "reliable_trades");
        long pending = fixture.mongo.count(Query.query(Criteria.where("counterparty").in(counterparties)
                .and("status").in("RESERVED", "SUBMITTED", "UNKNOWN")), "reliable_trades");
        long durableTrades = fixture.mongo.count(Query.query(Criteria.where("counterparty").in(counterparties)), "reliable_trades");
        invariant &= reserved.compareTo(AMOUNT.multiply(BigDecimal.valueOf(pending))) == 0
                && used.compareTo(AMOUNT.multiply(BigDecimal.valueOf(executions))) == 0;
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("name", name);
        result.put("accounts", accounts);
        result.put("attempted", requests);
        result.put("completed_samples", samples.size());
        result.put("errors", errors);
        result.put("status_counts", statuses);
        Map<String, Long> durableStatuses = new TreeMap<>();
        for (Document row : fixture.mongo.getCollection("reliable_trades").aggregate(List.of(
                new Document("$match", new Document("counterparty", new Document("$in", counterparties))),
                new Document("$group", new Document("_id", "$status").append("count", new Document("$sum", 1)))))) {
            durableStatuses.put(row.getString("_id"), ((Number) row.get("count")).longValue());
        }
        result.put("durable_status_counts", durableStatuses);
        result.put("transaction_attempts", transactions.getAttemptCount() - attemptsBefore);
        result.put("transaction_retries", transactions.getRetryCount() - retriesBefore);
        result.put("transaction_retry_exhaustions", transactions.getExhaustionCount() - exhaustionsBefore);
        result.put("unknown_commit_outcomes", transactions.getUnknownCommitCount() - unknownBefore);
        result.put("wall_time_ms", elapsed / 1_000_000.0);
        result.put("attempts_per_second", requests * 1_000_000_000.0 / elapsed);
        result.put("successful_executions_per_second", executions * 1_000_000_000.0 / elapsed);
        result.put("p50_ms", percentile(latency, 0.50));
        result.put("p95_ms", percentile(latency, 0.95));
        result.put("p99_ms", percentile(latency, 0.99));
        result.put("ledger_invariant_passed", invariant);
        result.put("all_requests_terminal", pending == 0 && durableTrades == requests && errors == 0);
        result.put("pending_trades", pending);
        result.put("durable_trades", durableTrades);
        result.put("used", used.toPlainString());
        result.put("reserved", reserved.toPlainString());
        result.put("samples", samples);
        return result;
    }

    private static double percentile(List<Double> sorted, double percentile) {
        return sorted.get(Math.max(0, (int) Math.ceil(sorted.size() * percentile) - 1));
    }

    private static int boundedProperty(String name, int fallback, int minimum, int maximum) {
        int value = Integer.parseInt(System.getProperty(name, String.valueOf(fallback)));
        if (value < minimum || value > maximum) throw new IllegalArgumentException(name + " outside bounded range");
        return value;
    }
}
