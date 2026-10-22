package com.gic.service;

import com.gic.exception.BusinessException;
import com.gic.model.Limit;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

@Service
public class LimitService {

    private final MongoTemplate mongoTemplate;

    public LimitService(MongoTemplate mongoTemplate) {
        this.mongoTemplate = mongoTemplate;
    }

    public Limit getAvailableLimit(String counterparty, String instrumentGroup) {
        Query query = new Query(Criteria.where("counterparty").is(counterparty)
                .and("instrumentGroup").is(instrumentGroup));
        // Older seed loaders could create duplicate rows. Keep reads, reservations,
        // and releases on one canonical row instead of pooling duplicate capacity.
        query.with(Sort.by(Sort.Direction.ASC, "_id"));
        return mongoTemplate.findOne(query, Limit.class);
    }

    public boolean checkAndReserveLimit(String counterparty, String instrumentGroup, Double amount) {
        validateAmount(amount);
        Limit limit = getAvailableLimit(counterparty, instrumentGroup);
        if (limit == null) {
            return false;
        }

        // The balance predicate and decrement execute together on MongoDB. A stale
        // read can identify the row, but cannot authorize an overspending write.
        Query query = new Query(Criteria.where("_id").is(limit.getId())
                .and("availableLimit").gte(amount).lte(Double.MAX_VALUE));
        Limit reserved = mongoTemplate.findAndModify(query,
                new Update().inc("availableLimit", -amount),
                FindAndModifyOptions.options().returnNew(true), Limit.class);
        return reserved != null;
    }

    public void confirmLimitUsage(String counterparty, String instrumentGroup, Double amount) {
        // The reservation already consumed capacity; execution does not debit twice.
        validateAmount(amount);
    }

    public void releaseLimitReservation(String counterparty, String instrumentGroup, Double amount) {
        validateAmount(amount);
        Limit limit = getAvailableLimit(counterparty, instrumentGroup);
        if (limit != null) {
            mongoTemplate.updateFirst(new Query(Criteria.where("_id").is(limit.getId())),
                    new Update().inc("availableLimit", amount), Limit.class);
        }
    }

    private void validateAmount(Double amount) {
        if (amount == null || !Double.isFinite(amount) || amount <= 0) {
            throw new BusinessException("INVALID_TRADE_AMOUNT", "Amount must be finite and greater than zero");
        }
    }
}
