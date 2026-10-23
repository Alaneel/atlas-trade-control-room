package com.gic.explanation;

import com.gic.reliability.DecisionCode;
import com.gic.reliability.TradeStatus;
import java.util.Date;
import java.time.Instant;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class MongoExplanationReader implements ExplanationReader {
    private final MongoTemplate mongo;
    public MongoExplanationReader(MongoTemplate mongo) { this.mongo = mongo; }

    @Override
    public ExplanationSnapshot load(String id) {
        if (id == null || !id.matches("[0-9a-f]{64}")) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "A valid trade reference is required");
        }
        Document before = trade(id);
        if (before == null) throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trade not found");
        Query query = Query.query(Criteria.where("tradeId").is(id)).with(Sort.by("sequence")).limit(65);
        for (String field : List.of("_id", "tradeId", "sequence", "status", "decisionCode", "ruleVersion", "availableAtDecision", "createdAt")) query.fields().include(field);
        List<Document> events = mongo.find(query, Document.class, "trade_events");
        Query receiptQuery = Query.query(Criteria.where("_id").is(id));
        for (String field : List.of("_id", "requestFingerprint", "counterparty", "instrumentGroup", "currency", "status", "amount", "createdAt")) receiptQuery.fields().include(field);
        Document receipt = mongo.findOne(receiptQuery, Document.class, "simulated_execution_receipts");
        Document after = trade(id);
        boolean stable = before.equals(after) && text(before, "counterparty") != null && text(before, "instrumentGroup") != null;
        boolean identityMatches = receipt == null || (Objects.equals(id, text(receipt, "_id"))
            && text(before, "requestFingerprint") != null
            && Objects.equals(text(before, "requestFingerprint"), text(receipt, "requestFingerprint"))
            && Objects.equals(text(before, "counterparty"), text(receipt, "counterparty"))
            && Objects.equals(text(before, "instrumentGroup"), text(receipt, "instrumentGroup"))
            && Objects.equals(text(before, "currency"), text(receipt, "currency")));
        // Raw projection avoids treating an unknown future enum or legacy double as valid current evidence.
        return new ExplanationSnapshot(id, integer(before, "version"), value(TradeStatus.class, before, "status"), money(before, "amount"),
            text(before, "currency"), value(DecisionCode.class, before, "decisionCode"), text(before, "ruleVersion"), money(before, "availableAtDecision"),
            events.stream().limit(64).map(event -> new ExplanationSnapshot.Event(text(event, "_id"), text(event, "tradeId"),
                integer(event, "sequence"), value(TradeStatus.class, event, "status"), value(DecisionCode.class, event, "decisionCode"),
                text(event, "ruleVersion"), money(event, "availableAtDecision"), instant(event, "createdAt"))).collect(Collectors.toList()),
            receipt == null ? null : new ExplanationSnapshot.Receipt(value(TradeStatus.class, receipt, "status"), money(receipt, "amount"),
                identityMatches, instant(receipt, "createdAt")), stable && events.size() <= 64);
    }
    private Document trade(String id) {
        Query query = Query.query(Criteria.where("_id").is(id));
        for (String field : List.of("_id", "version", "status", "amount", "currency", "decisionCode", "ruleVersion", "availableAtDecision",
                "requestFingerprint", "counterparty", "instrumentGroup")) query.fields().include(field);
        return mongo.findOne(query, Document.class, "reliable_trades");
    }
    private String text(Document record, String field) {
        Object value = record.get(field); return value instanceof String ? (String) value : null;
    }
    private long integer(Document record, String field) {
        Object value = record.get(field); return value instanceof Long || value instanceof Integer ? ((Number) value).longValue() : -1;
    }
    private String money(Document record, String field) {
        Object value = record.get(field);
        return value instanceof Decimal128 && ((Decimal128) value).isFinite() ? ((Decimal128) value).bigDecimalValue().toPlainString() : null;
    }
    private Instant instant(Document record, String field) {
        Object value = record.get(field); return value instanceof Date ? ((Date) value).toInstant() : null;
    }
    private <T extends Enum<T>> T value(Class<T> type, Document record, String field) {
        String raw = text(record, field);
        try { return raw == null ? null : Enum.valueOf(type, raw); }
        catch (IllegalArgumentException ex) { return null; }
    }
}
