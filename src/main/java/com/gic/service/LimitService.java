package com.gic.service;

import com.gic.model.Limit;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.stereotype.Service;

/** Limits mutate only inside ReliableTradeStore's ledger/trade/event transaction. */
@Service
public class LimitService {
    private final MongoTemplate mongo;
    public LimitService(MongoTemplate mongo) { this.mongo = mongo; }

    public Limit getAvailableLimit(String counterparty, String instrumentGroup) {
        return getAvailableLimit(counterparty, instrumentGroup, "USD");
    }

    public Limit getAvailableLimit(String counterparty, String instrumentGroup, String currency) {
        return mongo.findOne(Query.query(Criteria.where("counterparty").is(counterparty)
            .and("instrumentGroup").is(instrumentGroup).and("currency").is(currency)), Limit.class);
    }
}
