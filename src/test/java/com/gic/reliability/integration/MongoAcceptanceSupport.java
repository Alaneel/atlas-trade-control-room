package com.gic.reliability.integration;

import com.mongodb.ConnectionString;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;

import java.util.UUID;

/** Real replica-set fixtures. Every invocation owns exactly one generated database. */
final class MongoAcceptanceSupport implements AutoCloseable {
    final String databaseName = "atlas_acceptance_" + UUID.randomUUID().toString().replace("-", "");
    final MongoClient client;
    final SimpleMongoClientDatabaseFactory factory;
    final MongoTemplate mongo;
    final MongoTransactionManager transactions;

    MongoAcceptanceSupport() {
        String uri = System.getenv("ATLAS_MONGO_URI");
        if (uri == null || uri.isBlank()) {
            throw new IllegalStateException("ATLAS_MONGO_URI must name a local disposable Mongo replica set");
        }
        ConnectionString connection = new ConnectionString(uri);
        if (connection.getHosts().stream().anyMatch(host ->
                !(host.startsWith("127.0.0.1:") || host.equals("127.0.0.1")
                    || host.startsWith("localhost:") || host.equals("localhost")
                    || host.startsWith("[::1]:")))) {
            throw new IllegalArgumentException("Acceptance tests only connect to loopback Mongo hosts");
        }
        client = MongoClients.create(connection);
        factory = new SimpleMongoClientDatabaseFactory(client, databaseName);
        mongo = new MongoTemplate(factory);
        transactions = new com.gic.reliability.ReliabilityConfiguration().mongoTransactionManager(factory);
        mongo.getDb().runCommand(new org.bson.Document("ping", 1));
    }

    @Override
    public void close() {
        // Never derive this deletion target from the caller's URI/database name.
        if (!databaseName.matches("atlas_acceptance_[0-9a-f]{32}")) {
            throw new IllegalStateException("Refusing to drop a database not owned by this test invocation");
        }
        try {
            client.getDatabase(databaseName).drop();
        } finally {
            client.close();
        }
    }
}
