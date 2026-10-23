package com.gic.service;

import com.gic.exception.BusinessException;
import com.gic.model.Limit;
import com.gic.validation.MoneyAmounts;
import org.bson.Document;
import org.bson.types.Decimal128;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;
import java.math.BigDecimal;

@Service
public class LimitService {

    private final MongoTemplate mongoTemplate;

    public LimitService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Limit getAvailableLimit(String counterparty, String instrumentGroup) {
        Document limit = decimalLimit(counterparty, instrumentGroup);
        return limit == null ? null : mongoTemplate.getConverter().read(Limit.class, limit);
    }

    private Document decimalLimit(String counterparty, String instrumentGroup) {
        Query query = new Query(Criteria.where("counterparty").is(counterparty)
                .and("instrumentGroup").is(instrumentGroup));
        // Older seed loaders could create duplicate rows. Keep reads, reservations,
        // and releases on one canonical row instead of pooling duplicate capacity.
        query.with(Sort.by(Sort.Direction.ASC, "_id"));
        String collection = mongoTemplate.getCollectionName(Limit.class);
        for (int attempt = 0; attempt < 8; attempt++) {
            Document limit = mongoTemplate.findOne(query, Document.class, collection);
            if (limit == null) {
                return null;
            }
            Object raw = limit.get("availableLimit");
            BigDecimal balance = storedBalance(raw);
            if (raw instanceof Decimal128) {
                return limit;
            }
            // Numeric equality spans BSON types. Include the legacy type in this
            // compare-and-set, so migration cannot overwrite a decimal debit.
            Query legacy = new Query(new Criteria().andOperator(
                    Criteria.where("_id").is(limit.get("_id")),
                    Criteria.where("availableLimit").is(raw),
                    Criteria.where("availableLimit").type(bsonNumberType(raw))));
            Document migrated = mongoTemplate.findAndModify(legacy,
                    new Update().set("availableLimit", new Decimal128(balance)),
                    FindAndModifyOptions.options().returnNew(true), Document.class, collection);
            if (migrated != null) {
                return migrated;
            }
        }
        throw new BusinessException("LIMIT_BUSY", "Limit changed repeatedly during decimal migration; retry later");
    }

    public boolean checkAndReserveLimit(String counterparty, String instrumentGroup, BigDecimal amount) {
        BigDecimal debit = MoneyAmounts.requirePositive(amount);
        Document limit = decimalLimit(counterparty, instrumentGroup);
        if (limit == null) {
            return false;
        }

        // The balance predicate and decrement execute together on MongoDB. A stale
        // read can identify the row, but cannot authorize an overspending write.
        Query query = new Query(Criteria.where("_id").is(limit.get("_id"))
                .and("availableLimit").type(19).gte(new Decimal128(debit)));
        Document reserved = mongoTemplate.findAndModify(query,
                new Update().inc("availableLimit", new Decimal128(debit.negate())),
                FindAndModifyOptions.options().returnNew(true), Document.class,
                mongoTemplate.getCollectionName(Limit.class));
        return reserved != null;
    }

    public void confirmLimitUsage(String counterparty, String instrumentGroup, BigDecimal amount) {
        // The reservation already consumed capacity; execution does not debit twice.
        MoneyAmounts.requirePositive(amount);
    }

    public void releaseLimitReservation(String counterparty, String instrumentGroup, BigDecimal amount) {
        BigDecimal credit = MoneyAmounts.requirePositive(amount);
        Document limit = decimalLimit(counterparty, instrumentGroup);
        if (limit != null) {
            Query headroom = new Query(Criteria.where("_id").is(limit.get("_id"))
                    .and("availableLimit").type(19)
                    .lte(new Decimal128(MoneyAmounts.MAX_VALUE.subtract(credit))));
            if (mongoTemplate.updateFirst(headroom,
                    new Update().inc("availableLimit", new Decimal128(credit)),
                    mongoTemplate.getCollectionName(Limit.class)).getMatchedCount() != 1) {
                throw new BusinessException("INVALID_LIMIT_BALANCE", "Released capacity exceeds the supported decimal range");
            }
        }
    }

    private BigDecimal storedBalance(Object raw) {
        try {
            BigDecimal value;
            if (raw instanceof Decimal128) {
                value = ((Decimal128) raw).bigDecimalValue();
            } else if (raw instanceof Double && Double.isFinite((Double) raw)) {
                // Use the decimal spelling of the legacy double. Values such as
                // 0.30000000000000004 are rejected instead of rounded to 0.3.
                value = BigDecimal.valueOf((Double) raw);
            } else if (raw instanceof Integer || raw instanceof Long) {
                value = new BigDecimal(raw.toString());
            } else {
                throw new ArithmeticException("Unsupported or non-finite BSON balance");
            }
            return MoneyAmounts.requireBalance(value);
        } catch (ArithmeticException ex) {
            throw new BusinessException("INVALID_LIMIT_BALANCE", "Stored balance requires explicit decimal reconciliation");
        }
    }

    private int bsonNumberType(Object raw) {
        if (raw instanceof Double) return 1;
        if (raw instanceof Integer) return 16;
        if (raw instanceof Long) return 18;
        throw new BusinessException("INVALID_LIMIT_BALANCE", "Unsupported legacy balance type");
    }
}
