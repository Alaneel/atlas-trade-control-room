package com.gic.reliability.integration;

import com.gic.reliability.TransactionRunner;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Requires an exclusively owned Mongo instance started with enableTestCommands=1. */
@EnabledIfEnvironmentVariable(named = "ATLAS_MONGO_URI", matches = ".+")
@EnabledIfEnvironmentVariable(named = "ATLAS_MONGO_FAILPOINTS", matches = "true")
class MongoCommitFailureTest {
    private static MongoAcceptanceSupport fixture;
    private static TransactionRunner transactions;

    @BeforeAll
    static void connect() {
        fixture = new MongoAcceptanceSupport();
        fixture.mongo.createCollection("commit_probe");
        transactions = new TransactionRunner(fixture.transactions);
    }

    @AfterAll
    static void cleanup() {
        if (fixture != null) fixture.close();
    }

    @Test
    void unknownCommitResultRetriesTheSameCommitWithoutRepeatingBusinessCallback() {
        AtomicInteger callbacks = new AtomicInteger();
        String id = UUID.randomUUID().toString();
        failOnce("commitTransaction", 91, "UnknownTransactionCommitResult");
        try {
            transactions.run(() -> {
                callbacks.incrementAndGet();
                fixture.mongo.insert(new Document("_id", id).append("value", "committed_once"), "commit_probe");
                return id;
            });
            assertThat(callbacks).hasValue(1);
            assertThat(fixture.mongo.getCollection("commit_probe").countDocuments(new Document("_id", id)))
                    .isEqualTo(1);
        } finally {
            clearFailpoint();
        }
    }

    @Test
    void anAbortedTransientTransactionRerunsCallbackButCommitsOneRecord() {
        AtomicInteger callbacks = new AtomicInteger();
        String id = UUID.randomUUID().toString();
        failOnce("insert", 112, "TransientTransactionError");
        try {
            transactions.run(() -> {
                callbacks.incrementAndGet();
                fixture.mongo.insert(new Document("_id", id).append("value", "retry_after_abort"), "commit_probe");
                return id;
            });
            assertThat(callbacks).hasValue(2);
            assertThat(fixture.mongo.getCollection("commit_probe").countDocuments(new Document("_id", id)))
                    .isEqualTo(1);
        } finally {
            clearFailpoint();
        }
    }

    private static void failOnce(String command, int code, String label) {
        // This failpoint is process-wide. The separate opt-in is intentionally mandatory.
        fixture.client.getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand")
                .append("mode", new Document("times", 1))
                .append("data", new Document("failCommands", List.of(command))
                        .append("errorCode", code).append("errorLabels", List.of(label))));
    }

    private static void clearFailpoint() {
        fixture.client.getDatabase("admin").runCommand(new Document("configureFailPoint", "failCommand").append("mode", "off"));
    }
}
