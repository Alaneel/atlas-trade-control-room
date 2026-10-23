package com.gic.config;

import com.opencsv.CSVReader;
import com.opencsv.exceptions.CsvException;
import com.gic.model.Instrument;
import com.gic.model.Limit;
import com.gic.validation.MoneyAmounts;
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
import java.util.UUID;

@Configuration
@ConditionalOnProperty(name = "app.seed-data.enabled", havingValue = "true", matchIfMissing = true)
public class MockDataInitializer {
    private final MongoTemplate mongoTemplate;

    public MockDataInitializer(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    @Bean
    CommandLineRunner initDatabase() {
        return args -> {
            loadInstruments();
            loadLimits();
        };
    }

    private void loadInstruments() throws IOException, CsvException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                new ClassPathResource("instruments.csv").getInputStream(), StandardCharsets.UTF_8))) {
            List<String[]> rows = reader.readAll();
            rows.remove(0); // Remove header row

            for (String[] row : rows) {
                Instrument instrument = new Instrument();
                instrument.setInstrumentGroup(row[0]);
                instrument.setInstrument(row[1]);
                instrument.setDepartment(row[2]);
                instrument.setRiskCountry(row[3]);
                instrument.setExchange(row[4]);
                instrument.setTradeCCY(row[5]);
                instrument.setSettlementCCY(row[6]);
                seedInstrument(instrument);
            }
        }
    }

    private void loadLimits() throws IOException, CsvException {
        try (CSVReader reader = new CSVReader(new InputStreamReader(
                new ClassPathResource("limit.csv").getInputStream(), StandardCharsets.UTF_8))) {
            List<String[]> rows = reader.readAll();
            rows.remove(0); // Remove header row

            DateTimeFormatter formatter = DateTimeFormatter.ofPattern("d/M/yy");

            for (String[] row : rows) {
                Limit limit = new Limit();
                limit.setInstrumentGroup(row[0]);
                limit.setCounterparty(row[1]);
                limit.setCurrency(row[2]);
                limit.setAvailableLimit(new BigDecimal(row[3].replace(",", "")));
                limit.setDataDate(LocalDate.parse(row[4], formatter));
                seedLimit(limit);
            }
        }
    }

    void seedInstrument(Instrument instrument) {
        Query naturalKey = Query.query(Criteria.where("instrumentGroup").is(instrument.getInstrumentGroup())
                .and("instrument").is(instrument.getInstrument())
                .and("department").is(instrument.getDepartment())
                .and("riskCountry").is(instrument.getRiskCountry())
                .and("exchange").is(instrument.getExchange())
                .and("tradeCCY").is(instrument.getTradeCCY())
                .and("settlementCCY").is(instrument.getSettlementCCY()));
        // Preserve legacy records too: restarting must not append another snapshot.
        if (mongoTemplate.exists(naturalKey, Instrument.class)) {
            return;
        }
        String key = String.join("\u001f", instrument.getInstrumentGroup(), instrument.getInstrument(),
                instrument.getDepartment(), instrument.getRiskCountry(), instrument.getExchange(),
                instrument.getTradeCCY(), instrument.getSettlementCCY());
        Query identity = Query.query(Criteria.where("_id").is(seedId("instrument", key)));
        Update insert = new Update()
                .setOnInsert("instrumentGroup", instrument.getInstrumentGroup())
                .setOnInsert("instrument", instrument.getInstrument())
                .setOnInsert("department", instrument.getDepartment())
                .setOnInsert("riskCountry", instrument.getRiskCountry())
                .setOnInsert("exchange", instrument.getExchange())
                .setOnInsert("tradeCCY", instrument.getTradeCCY())
                .setOnInsert("settlementCCY", instrument.getSettlementCCY());
        mongoTemplate.upsert(identity, insert, Instrument.class);
    }

    void seedLimit(Limit limit) {
        Query naturalKey = Query.query(Criteria.where("counterparty").is(limit.getCounterparty())
                .and("instrumentGroup").is(limit.getInstrumentGroup()));
        if (mongoTemplate.exists(naturalKey, Limit.class)) {
            return;
        }
        String key = String.join("\u001f", limit.getCounterparty(), limit.getInstrumentGroup());
        Query identity = Query.query(Criteria.where("_id").is(seedId("limit", key)));
        // Only insert initial capacity; never restore capacity already spent by trades.
        Update insert = new Update()
                .setOnInsert("counterparty", limit.getCounterparty())
                .setOnInsert("instrumentGroup", limit.getInstrumentGroup())
                .setOnInsert("currency", limit.getCurrency())
                .setOnInsert("availableLimit", MoneyAmounts.requireBalance(limit.getAvailableLimit()))
                .setOnInsert("dataDate", limit.getDataDate());
        mongoTemplate.upsert(identity, insert, Limit.class);
    }

    private String seedId(String kind, String key) {
        return "seed-" + kind + "-" + UUID.nameUUIDFromBytes(key.getBytes(StandardCharsets.UTF_8));
    }
}
