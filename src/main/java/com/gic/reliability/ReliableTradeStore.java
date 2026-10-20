package com.gic.reliability;

import com.gic.model.Limit;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import javax.annotation.PostConstruct;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.index.Index;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class ReliableTradeStore {
    private final MongoTemplate mongo;
    private final TransactionRunner transactions;

    public ReliableTradeStore(MongoTemplate mongo, TransactionRunner transactions) {
        this.mongo = mongo;
        this.transactions = transactions;
    }

    @PostConstruct
    public void initialize() {
        org.bson.Document hello = mongo.executeCommand(new org.bson.Document("hello", 1));
        if (hello.getString("setName") == null) {
            throw new IllegalStateException("Atlas reliable execution requires a MongoDB replica set; standalone MongoDB cannot commit ledger/trade/event transactions");
        }
        // Collections and indexes must exist before a multi-document transaction starts.
        for (Class<?> type : new Class<?>[]{Limit.class, TradeOrder.class, TradeEvent.class, ExecutionReceipt.class}) {
            if (!mongo.collectionExists(type)) {
                try { mongo.createCollection(type); }
                catch (org.springframework.dao.DataAccessException ex) {
                    if (!mongo.collectionExists(type)) { throw ex; }
                }
            }
        }
        validateLedgers();
        mongo.indexOps(Limit.class).ensureIndex(new Index().on("counterparty", Sort.Direction.ASC)
            .on("instrumentGroup", Sort.Direction.ASC).on("currency", Sort.Direction.ASC).unique().named("limit_scope"));
        mongo.indexOps(TradeOrder.class).ensureIndex(new Index().on("status", Sort.Direction.ASC)
            .on("nextAttemptAt", Sort.Direction.ASC).on("leaseUntil", Sort.Direction.ASC));
        mongo.indexOps(TradeEvent.class).ensureIndex(new Index().on("tradeId", Sort.Direction.ASC)
            .on("sequence", Sort.Direction.ASC).unique());
    }

    private void validateLedgers() {
        // Read raw BSON: mapping a legacy double to BigDecimal would hide lost precision.
        try (com.mongodb.client.MongoCursor<org.bson.Document> cursor = mongo.getCollection("limits")
                .find().projection(new org.bson.Document("counterparty", 1).append("instrumentGroup", 1)
                    .append("currency", 1).append("totalLimit", 1).append("availableLimit", 1)
                    .append("reservedLimit", 1).append("usedLimit", 1)).iterator()) {
            while (cursor.hasNext()) {
                org.bson.Document account = cursor.next();
                for (String field : new String[]{"counterparty", "instrumentGroup", "currency"}) {
                    Object value = account.get(field);
                    if (!(value instanceof String) || ((String) value).isBlank()) {
                        throw invalidLedger(account, "missing scope " + field);
                    }
                }
                java.math.BigDecimal total = exactBalance(account, "totalLimit");
                java.math.BigDecimal available = exactBalance(account, "availableLimit");
                java.math.BigDecimal reserved = exactBalance(account, "reservedLimit");
                java.math.BigDecimal used = exactBalance(account, "usedLimit");
                if (total.compareTo(available.add(reserved).add(used)) != 0) {
                    throw invalidLedger(account, "total does not equal available + reserved + used");
                }
            }
        }
    }

    private java.math.BigDecimal exactBalance(org.bson.Document account, String field) {
        Object raw = account.get(field);
        if (!(raw instanceof org.bson.types.Decimal128) || !((org.bson.types.Decimal128) raw).isFinite()) {
            throw invalidLedger(account, field + " must be finite BSON Decimal128");
        }
        java.math.BigDecimal amount = ((org.bson.types.Decimal128) raw).bigDecimalValue();
        try {
            if (amount.signum() < 0) { throw invalidLedger(account, field + " is negative"); }
            return amount.setScale(2, java.math.RoundingMode.UNNECESSARY);
        } catch (ArithmeticException ex) {
            throw invalidLedger(account, field + " contains fractional cents");
        }
    }

    private IllegalStateException invalidLedger(org.bson.Document account, String reason) {
        return new IllegalStateException("Invalid or legacy ledger " + account.get("_id") + ": " + reason
            + ". Use a fresh database or perform an audited migration; balances are never inferred.");
    }

    public TradeOrder create(TradeOrder proposal) {
        return transactions.run(() -> {
            TradeOrder existing = mongo.findById(proposal.getId(), TradeOrder.class);
            if (existing != null) {
                if (!existing.getRequestFingerprint().equals(proposal.getRequestFingerprint())) {
                    throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was already used for a different trade");
                }
                return existing;
            }
            // Retry callbacks must not mutate the proposal shared between attempts.
            TradeOrder trade = copy(proposal);
            if (trade.getStatus() == TradeStatus.RESERVED) {
                Query account = account(trade);
                account.addCriteria(Criteria.where("availableLimit").gte(trade.getAmount())
                    .and("totalLimit").exists(true).and("reservedLimit").exists(true).and("usedLimit").exists(true));
                long reserved = mongo.updateFirst(account, new Update().inc("availableLimit", trade.getAmount().negate())
                    .inc("reservedLimit", trade.getAmount()), Limit.class).getModifiedCount();
                if (reserved != 1) {
                    trade.setStatus(TradeStatus.REJECTED);
                    trade.setMessage("Insufficient capacity or no initialized exact-currency limit");
                }
            }
            mongo.insert(trade);
            mongo.insert(new TradeEvent(trade));
            return trade;
        });
    }

    public java.util.Optional<TradeOrder> existing(TradeOrder proposal) {
        TradeOrder existing = mongo.findById(proposal.getId(), TradeOrder.class);
        if (existing != null && !existing.getRequestFingerprint().equals(proposal.getRequestFingerprint())) {
            throw new ResponseStatusException(HttpStatus.CONFLICT, "Idempotency-Key was already used for a different trade");
        }
        return java.util.Optional.ofNullable(existing);
    }

    private TradeOrder copy(TradeOrder source) {
        TradeOrder copy = new TradeOrder();
        org.springframework.beans.BeanUtils.copyProperties(source, copy);
        return copy;
    }

    public TradeOrder get(String id) {
        TradeOrder trade = mongo.findById(id, TradeOrder.class);
        if (trade == null) { throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Trade not found"); }
        return trade;
    }

    public List<TradeOrder> list() {
        return mongo.find(new Query().with(Sort.by(Sort.Direction.DESC, "createdAt")).limit(200), TradeOrder.class);
    }

    public List<TradeEvent> events(String id) {
        get(id);
        return mongo.find(Query.query(Criteria.where("tradeId").is(id))
            .with(Sort.by("sequence")).limit(1000), TradeEvent.class);
    }

    public List<TradeOrder> due(int batchSize) {
        if (batchSize < 1 || batchSize > 100) { throw new IllegalArgumentException("Recovery batch size must be 1..100"); }
        Instant now = Instant.now();
        return mongo.find(Query.query(dueCriteria(now)).with(Sort.by("nextAttemptAt")).limit(batchSize), TradeOrder.class);
    }

    private Criteria dueCriteria(Instant now) {
        return Criteria.where("status").in(TradeStatus.RESERVED, TradeStatus.SUBMITTED, TradeStatus.UNKNOWN)
            .and("nextAttemptAt").lte(now).and("leaseUntil").lte(now);
    }

    /** A CAS lease prevents concurrent drivers; stable venue IDs also protect expired holders. */
    public TradeOrder claim(String id, long leaseMillis) {
        return claim(id, leaseMillis, false);
    }

    public TradeOrder claimForReconciliation(String id, long leaseMillis) {
        return claim(id, leaseMillis, true);
    }

    private TradeOrder claim(String id, long leaseMillis, boolean reconciliation) {
        return transactions.run(() -> {
            Instant now = Instant.now();
            Criteria eligible = reconciliation
                ? Criteria.where("status").in(TradeStatus.SUBMITTED, TradeStatus.UNKNOWN).and("leaseUntil").lte(now)
                : dueCriteria(now);
            Query query = Query.query(new Criteria().andOperator(Criteria.where("_id").is(id), eligible));
            TradeOrder trade = mongo.findAndModify(query, new Update().set("leaseToken", UUID.randomUUID().toString())
                .set("leaseUntil", now.plusMillis(leaseMillis)).inc("attempts", 1),
                FindAndModifyOptions.options().returnNew(true), TradeOrder.class);
            if (trade == null) { return null; }
            if (trade.getStatus() == TradeStatus.RESERVED) {
                trade.setStatus(TradeStatus.SUBMITTED);
                trade.setVersion(trade.getVersion() + 1);
                trade.setUpdatedAt(now);
                trade.setMessage("Submitted with stable execution identity");
                mongo.save(trade);
                mongo.insert(new TradeEvent(trade));
            }
            return trade;
        });
    }

    public TradeOrder uncertain(TradeOrder claimed, String message, long retryMillis) {
        return transition(claimed, TradeStatus.UNKNOWN, message, retryMillis);
    }

    public TradeOrder settle(TradeOrder claimed, ExecutionReceipt receipt) {
        if (!claimed.getId().equals(receipt.getId()) || receipt.getAmount() == null
                || claimed.getAmount().compareTo(receipt.getAmount()) != 0 || receipt.getStatus() == null
                || !receipt.getStatus().terminal()
                || !java.util.Objects.equals(claimed.getRequestFingerprint(), receipt.getRequestFingerprint())
                || !java.util.Objects.equals(claimed.getCounterparty(), receipt.getCounterparty())
                || !java.util.Objects.equals(claimed.getInstrumentGroup(), receipt.getInstrumentGroup())
                || !java.util.Objects.equals(claimed.getCurrency(), receipt.getCurrency())) {
            throw new IllegalStateException("Execution receipt identity/amount/status mismatch");
        }
        return transition(claimed, receipt.getStatus(), "Confirmed by execution receipt", 0);
    }

    private TradeOrder transition(TradeOrder claimed, TradeStatus status, String message, long retryMillis) {
        return transactions.run(() -> {
            TradeOrder current = mongo.findOne(Query.query(Criteria.where("_id").is(claimed.getId())
                .and("leaseToken").is(claimed.getLeaseToken()).and("status").in(TradeStatus.SUBMITTED, TradeStatus.UNKNOWN)), TradeOrder.class);
            if (current == null) { return get(claimed.getId()); }
            if (status.terminal()) {
                Query account = account(current);
                account.addCriteria(Criteria.where("reservedLimit").gte(current.getAmount()));
                Update ledger = new Update().inc("reservedLimit", current.getAmount().negate());
                if (status == TradeStatus.EXECUTED) { ledger.inc("usedLimit", current.getAmount()); }
                else { ledger.inc("availableLimit", current.getAmount()); }
                if (mongo.updateFirst(account, ledger, Limit.class).getModifiedCount() != 1) {
                    throw new IllegalStateException("Reserved capacity missing; refusing an inconsistent settlement");
                }
            }
            current.setStatus(status);
            current.setMessage(message);
            current.setVersion(current.getVersion() + 1);
            current.setUpdatedAt(Instant.now());
            current.setLeaseToken(null);
            current.setLeaseUntil(Instant.EPOCH);
            current.setNextAttemptAt(Instant.now().plusMillis(retryMillis));
            mongo.save(current);
            mongo.insert(new TradeEvent(current));
            return current;
        });
    }

    private Query account(TradeOrder trade) {
        return Query.query(Criteria.where("counterparty").is(trade.getCounterparty())
            .and("instrumentGroup").is(trade.getInstrumentGroup()).and("currency").is(trade.getCurrency()));
    }
}
