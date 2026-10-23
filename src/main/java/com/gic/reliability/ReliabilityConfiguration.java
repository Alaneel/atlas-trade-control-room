package com.gic.reliability;

import com.mongodb.ReadConcern;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import java.util.concurrent.TimeUnit;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class ReliabilityConfiguration {
    @Bean
    public org.springframework.boot.autoconfigure.mongo.MongoClientSettingsBuilderCustomizer reliableWriteConcern() {
        return builder -> builder.writeConcern(WriteConcern.MAJORITY.withWTimeout(5, TimeUnit.SECONDS));
    }

    @Bean
    public MongoTransactionManager mongoTransactionManager(MongoDatabaseFactory factory) {
        return new MongoTransactionManager(factory, TransactionOptions.builder()
            .readConcern(ReadConcern.SNAPSHOT).writeConcern(WriteConcern.MAJORITY.withWTimeout(5, TimeUnit.SECONDS))
            .maxCommitTime(5L, TimeUnit.SECONDS).build()) {
            @Override
            protected void doCommit(MongoTransactionObject transaction) throws Exception {
                for (int attempt = 0; ; attempt++) {
                    try {
                        super.doCommit(transaction);
                        return;
                    } catch (Exception ex) {
                        if (!TransactionRunner.hasLabel(ex, "UnknownTransactionCommitResult") || attempt >= 4) { throw ex; }
                        // Repeat commit on the same session/transaction, never the business callback.
                    }
                }
            }
        };
    }
}
