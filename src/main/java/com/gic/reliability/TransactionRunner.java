package com.gic.reliability;

import com.mongodb.MongoException;
import java.util.function.Supplier;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.web.server.ResponseStatusException;

/** Retry only aborted transactions; an uncertain commit must not replay its callback. */
@Component
public class TransactionRunner {
    private static final int MAX_ATTEMPTS = 24;
    private static final long RETRY_BUDGET_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final TransactionTemplate template;
    private final AtomicLong attempts = new AtomicLong();
    private final AtomicLong retries = new AtomicLong();
    private final AtomicLong exhaustions = new AtomicLong();
    private final AtomicLong unknownCommits = new AtomicLong();

    public TransactionRunner(MongoTransactionManager manager) {
        template = new TransactionTemplate(manager);
        template.setTimeout(10);
    }

    public <T> T run(Supplier<T> action) {
        long started = System.nanoTime();
        for (int attempt = 0; attempt < MAX_ATTEMPTS; attempt++) {
            try {
                attempts.incrementAndGet();
                return template.execute(status -> action.get());
            } catch (RuntimeException ex) {
                if (hasLabel(ex, "UnknownTransactionCommitResult")) {
                    unknownCommits.incrementAndGet();
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
                        "Commit outcome unknown; retry the identical idempotency key or read its trade", ex);
                }
                if (!(hasLabel(ex, "TransientTransactionError") || ex instanceof DuplicateKeyException)) { throw ex; }
                long remaining = RETRY_BUDGET_NANOS - (System.nanoTime() - started);
                if (attempt == MAX_ATTEMPTS - 1 || remaining <= 0) { break; }
                try {
                    // Full jitter desynchronizes distributed contenders; no process-local account lock.
                    long ceilingMillis = Math.min(5L << Math.min(attempt, 6), 200L);
                    long sleepNanos = Math.min(TimeUnit.MILLISECONDS.toNanos(
                        ThreadLocalRandom.current().nextLong(ceilingMillis + 1)), remaining);
                    TimeUnit.NANOSECONDS.sleep(sleepNanos);
                    if (System.nanoTime() - started >= RETRY_BUDGET_NANOS) { break; }
                    retries.incrementAndGet();
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Transaction interrupted", interrupted);
                }
            }
        }
        exhaustions.incrementAndGet();
        throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE,
            "Transaction contention; retry using the identical Idempotency-Key");
    }

    /** Cumulative counters for this runner; benchmark snapshots must report deltas. */
    public long getAttemptCount() { return attempts.get(); }
    public long getRetryCount() { return retries.get(); }
    public long getExhaustionCount() { return exhaustions.get(); }
    public long getUnknownCommitCount() { return unknownCommits.get(); }

    public static boolean hasLabel(Throwable error, String label) {
        for (Throwable t = error; t != null; t = t.getCause()) {
            if (t instanceof MongoException && ((MongoException) t).hasErrorLabel(label)) { return true; }
        }
        return false;
    }
}
