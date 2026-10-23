package com.gic.config;

import com.gic.model.Instrument;
import com.gic.model.Limit;
import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import org.bson.types.Decimal128;
import org.springframework.boot.CommandLineRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;

import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;

/** Development fixture import: insert missing records, never reset a live ledger. */
@Configuration
@ConditionalOnProperty(name = "atlas.seed.enabled", havingValue = "true", matchIfMissing = true)
public class MockDataInitializer {
    private final MongoTemplate mongo;

    public MockDataInitializer(MongoTemplate mongo) {
        this.mongo = mongo;
    }

    @Bean
    CommandLineRunner initDatabase() {
        return args -> {
            org.bson.Document legacy = new org.bson.Document("$or", java.util.Arrays.asList(
                new org.bson.Document("totalLimit", new org.bson.Document("$exists", false)),
                new org.bson.Document("reservedLimit", new org.bson.Document("$exists", false)),
                new org.bson.Document("usedLimit", new org.bson.Document("$exists", false)),
                new org.bson.Document("availableLimit", new org.bson.Document("$type", "double"))));
            if (mongo.getCollection("limits").countDocuments(legacy) > 0) {
                throw new IllegalStateException("Legacy limits detected. Use a fresh atlas_reliable database; "
                    + "existing ledgers require an audited migration (see docs/RELIABLE_EXECUTION.md).");
            }
            for (String[] row : read("instruments.csv")) seedInstrument(row);
            DateTimeFormatter dates = DateTimeFormatter.ofPattern("d/M/yy");
            for (String[] row : read("limit.csv")) {
                seedLimit(row[1], row[0], row[2], new BigDecimal(row[3].replace(",", "")),
                    LocalDate.parse(row[4], dates));
            }
            seedInstrument(new String[]{"EQUITY", "AAPL", "GLOBAL_MARKETS", "US", "NASDAQ", "USD", "USD"});
            seedLimit("NORTHSTAR", "EQUITY", "USD", new BigDecimal("100.00"), LocalDate.of(2026, 10, 20));
        };
    }

    private List<String[]> read(String name) throws IOException, CsvException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                new ClassPathResource(name).getInputStream(), StandardCharsets.UTF_8))) {
            List<String[]> rows = reader.readAll();
            if (!rows.isEmpty()) rows.remove(0);
            return rows;
        }
    }

    private void seedInstrument(String[] row) {
        Query query = Query.query(Criteria.where("instrumentGroup").is(row[0])
            .and("instrument").is(row[1]).and("department").is(row[2])
            .and("riskCountry").is(row[3]).and("exchange").is(row[4])
            .and("tradeCCY").is(row[5]).and("settlementCCY").is(row[6]));
        Update insert = new Update().setOnInsert("instrumentGroup", row[0])
            .setOnInsert("instrument", row[1]).setOnInsert("department", row[2])
            .setOnInsert("riskCountry", row[3]).setOnInsert("exchange", row[4])
            .setOnInsert("tradeCCY", row[5]).setOnInsert("settlementCCY", row[6]);
        String identity = java.util.UUID.nameUUIDFromBytes(String.join("|", row)
            .getBytes(StandardCharsets.UTF_8)).toString();
        insert.setOnInsert("_id", identity);
        mongo.upsert(query, insert, Instrument.class);
    }

    private void seedLimit(String counterparty, String group, String currency,
                           BigDecimal amount, LocalDate date) {
        Query query = Query.query(Criteria.where("counterparty").is(counterparty)
            .and("instrumentGroup").is(group).and("currency").is(currency));
        mongo.upsert(query, new Update().setOnInsert("counterparty", counterparty)
            .setOnInsert("instrumentGroup", group).setOnInsert("currency", currency)
            .setOnInsert("totalLimit", new Decimal128(amount.setScale(2)))
            .setOnInsert("availableLimit", new Decimal128(amount.setScale(2)))
            .setOnInsert("reservedLimit", new Decimal128(new BigDecimal("0.00")))
            .setOnInsert("usedLimit", new Decimal128(new BigDecimal("0.00")))
            .setOnInsert("dataDate", date), Limit.class);
    }
}
